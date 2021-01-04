package gov.cms.dpc.aggregation.engine;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.PerformanceOptionsEnum;
import com.codahale.metrics.MetricRegistry;
import com.typesafe.config.ConfigFactory;
import gov.cms.dpc.aggregation.service.EveryoneGetsDataLookBackServiceImpl;
import gov.cms.dpc.aggregation.service.LookBackAnswer;
import gov.cms.dpc.aggregation.service.LookBackService;
import gov.cms.dpc.aggregation.util.AggregationUtils;
import gov.cms.dpc.bluebutton.client.MockBlueButtonClient;
import gov.cms.dpc.common.utils.NPIUtil;
import gov.cms.dpc.fhir.hapi.ContextUtils;
import gov.cms.dpc.queue.IJobQueue;
import gov.cms.dpc.queue.JobStatus;
import gov.cms.dpc.queue.MemoryBatchQueue;
import gov.cms.dpc.queue.models.JobQueueBatch;
import gov.cms.dpc.queue.models.JobQueueBatchFile;
import gov.cms.dpc.testing.BufferedLoggerHandler;
import io.reactivex.disposables.Disposable;
import org.apache.commons.lang3.time.DateUtils;
import org.hl7.fhir.dstu3.model.OperationOutcome;
import org.hl7.fhir.dstu3.model.ResourceType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static gov.cms.dpc.aggregation.service.LookBackAnalyzer.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@ExtendWith(BufferedLoggerHandler.class)
class BatchAggregationEngineTest {
    private static final UUID aggregatorID = UUID.randomUUID();
    private static final String TEST_PROVIDER_ID = "1";
    private IJobQueue queue;
    private AggregationEngine engine;
    private JobBatchProcessor jobBatchProcessor;
    private Disposable subscribe;
    private LookBackService lookBackService;

    static private FhirContext fhirContext = FhirContext.forDstu3();
    static private MetricRegistry metricRegistry = new MetricRegistry();
    static private String exportPath;
    static private OperationsConfig operationsConfig;

    @BeforeAll
    static void setupAll() throws ParseException {
        fhirContext.setPerformanceOptions(PerformanceOptionsEnum.DEFERRED_MODEL_SCANNING);
        final var config = ConfigFactory.load("testing.conf").getConfig("dpc.aggregation");
        exportPath = config.getString("exportPath");
        operationsConfig = new OperationsConfig(10, exportPath, 3, new SimpleDateFormat("dd/MM/yyyy").parse("03/01/2015"));
        AggregationEngine.setGlobalErrorHandler();
        ContextUtils.prefetchResourceModels(fhirContext, JobQueueBatch.validResourceTypes);
    }

    @BeforeEach
    void setupEach() {
        queue = new MemoryBatchQueue(100);
        final var bbclient = Mockito.spy(new MockBlueButtonClient(fhirContext));
        lookBackService = Mockito.spy(EveryoneGetsDataLookBackServiceImpl.class);
        jobBatchProcessor = Mockito.spy(new JobBatchProcessor(bbclient, fhirContext, metricRegistry, operationsConfig, lookBackService));
        engine = Mockito.spy(new AggregationEngine(aggregatorID, queue, operationsConfig, jobBatchProcessor));
        engine.queueRunning.set(true);
        subscribe = Mockito.mock(Disposable.class);
        doReturn(false).when(subscribe).isDisposed();
        engine.setSubscribe(subscribe);
    }

    /**
     * Test if a engine can handle a simple job with one resource type, one test provider, and one patient.
     */
    @Test
    void largeJobTestSingleResource() {
        Mockito.doReturn(UUID.randomUUID().toString()).when(lookBackService).getPractitionerNPIFromRoster(Mockito.any(), Mockito.anyString(), Mockito.anyString());
        // Make a simple job with one resource type
        final var orgID = UUID.randomUUID();
        final var jobID = queue.createJob(
                orgID,
                TEST_PROVIDER_ID,
                Collections.singletonList(MockBlueButtonClient.TEST_PATIENT_MBIS.get(0)),
                Collections.singletonList(ResourceType.ExplanationOfBenefit),
                MockBlueButtonClient.TEST_LAST_UPDATED.minusSeconds(1),
                MockBlueButtonClient.BFD_TRANSACTION_TIME,
                null, true);

        // Do the job
        queue.claimBatch(engine.getAggregatorID())
                .ifPresent(engine::processJobBatch);

        // Look at the result
        final var completeJob = queue.getJobBatches(jobID).stream().findFirst().orElseThrow();
        assertEquals(JobStatus.COMPLETED, completeJob.getStatus());
        final List<JobQueueBatchFile> sorted = completeJob.getJobQueueBatchFiles().stream().sorted(Comparator.comparingInt(JobQueueBatchFile::getSequence)).collect(Collectors.toList());
        assertAll(() -> assertEquals(4, sorted.size()),
                () -> assertEquals(10, sorted.get(0).getCount()),
                () -> assertEquals(2, sorted.get(3).getCount()));

        // Look at the output files
        final var outputFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.ExplanationOfBenefit, 0);
        assertTrue(Files.exists(Path.of(outputFilePath)));
        final var errorFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.OperationOutcome, 0);
        assertFalse(Files.exists(Path.of(errorFilePath)), "expect no error file");
    }

    /**
     * Test if a engine can handle a simple job with one resource type, one test provider, and one patient.
     */
    @Test
    void largeJobTest() {
        Mockito.doReturn(UUID.randomUUID().toString()).when(lookBackService).getPractitionerNPIFromRoster(Mockito.any(), Mockito.anyString(), Mockito.anyString());

        // Make a simple job with one resource type
        final var orgID = UUID.randomUUID();
        final var jobID = queue.createJob(
                orgID,
                TEST_PROVIDER_ID,
                MockBlueButtonClient.TEST_PATIENT_MBIS,
                JobQueueBatch.validResourceTypes,
                MockBlueButtonClient.TEST_LAST_UPDATED.minusSeconds(1),
                MockBlueButtonClient.BFD_TRANSACTION_TIME,
                null, true);

        // Do the job
        queue.claimBatch(engine.getAggregatorID())
                .ifPresent(engine::processJobBatch);

        // Look at the result
        final var completeJob = queue.getJobBatches(jobID).stream().findFirst().orElseThrow();
        assertEquals(JobStatus.COMPLETED, completeJob.getStatus());

        // Look at the output files
        completeJob.getJobQueueBatchFiles()
                .forEach(batchFile -> {
                    final var outputFilePath = String.format("%s/%s.ndjson", exportPath, batchFile.getFileName());
                    final File file = new File(Path.of(outputFilePath).toString());
                    assertAll(() -> assertNotNull(file, "Should have input file"),
                            () -> assertArrayEquals(AggregationUtils.generateChecksum(file), batchFile.getChecksum(), "Should have checksum"),
                            () -> assertEquals(file.length(), batchFile.getFileLength(), "Should have matching file length"));
                });

        final var errorFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.OperationOutcome, 0);
        assertFalse(Files.exists(Path.of(errorFilePath)), "expect no error file");
    }

    /**
     * Test if a engine can handle a simple job with one resource type, one test provider, and one patient.
     */
    @Test
    void largeJobWithBadPatientTest() {
        final var orgID = UUID.randomUUID();

        Mockito.doReturn(UUID.randomUUID().toString()).when(lookBackService).getPractitionerNPIFromRoster(Mockito.any(), Mockito.anyString(), Mockito.anyString());
        Mockito.doReturn(null).when(lookBackService).getPractitionerNPIFromRoster(orgID,TEST_PROVIDER_ID, null);

        // Make a simple job with one resource type
        final var jobID = queue.createJob(
                orgID,
                TEST_PROVIDER_ID,
                MockBlueButtonClient.TEST_PATIENT_WITH_BAD_IDS,
                Collections.singletonList(ResourceType.ExplanationOfBenefit),
                MockBlueButtonClient.TEST_LAST_UPDATED.minusSeconds(1),
                MockBlueButtonClient.BFD_TRANSACTION_TIME,
                null, true);

        // Do the job
        queue.claimBatch(engine.getAggregatorID())
                .ifPresent(engine::processJobBatch);

        // Look at the result
        final var completeJob = queue.getJobBatches(jobID).stream().findFirst().orElseThrow();
        assertEquals(JobStatus.COMPLETED, completeJob.getStatus());
        assertAll(
                () -> assertEquals(5, completeJob.getJobQueueBatchFiles().size(), String.format("Unexpected JobModel: %s", completeJob.toString())),
                () -> assertTrue(completeJob.getJobQueueFile(ResourceType.ExplanationOfBenefit).isPresent(), "Expect a EOB"),
                () -> assertFalse(completeJob.getJobQueueFile(ResourceType.OperationOutcome).isEmpty(), "Expect an error"));

        // Look at the output files
        final var outputFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.ExplanationOfBenefit, 0);
        assertTrue(Files.exists(Path.of(outputFilePath)));
        final var errorFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.OperationOutcome, 0);
        assertTrue(Files.exists(Path.of(errorFilePath)), "expect error file for failed patient");
    }

    @Test
    void lookBackDateCriteriaMismatch() throws IOException {
        final var orgID = UUID.randomUUID();
        final var npi = NPIUtil.generateNPI();

        Mockito.doReturn(UUID.randomUUID().toString()).when(lookBackService).getPractitionerNPIFromRoster(Mockito.any(), Mockito.anyString(), Mockito.anyString());
        Mockito.doReturn(new LookBackAnswer(npi, npi, 1, new Date())
                .addEobBillingPeriod(DateUtils.addYears(new Date(), -1))
                .addEobOrganization(npi)
                .addEobProviders(List.of(npi))).when(lookBackService).getLookBackAnswer(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyLong());

        // Make a simple job with one resource type
        final var jobID = queue.createJob(
                orgID,
                TEST_PROVIDER_ID,
                MockBlueButtonClient.TEST_PATIENT_MBIS.subList(0,1),
                Collections.singletonList(ResourceType.ExplanationOfBenefit),
                MockBlueButtonClient.TEST_LAST_UPDATED.minusSeconds(1),
                MockBlueButtonClient.BFD_TRANSACTION_TIME,
                null, true);

        // Do the job
        queue.claimBatch(engine.getAggregatorID())
                .ifPresent(engine::processJobBatch);

        // Look at the result
        final var completeJob = queue.getJobBatches(jobID).stream().findFirst().orElseThrow();
        assertEquals(JobStatus.COMPLETED, completeJob.getStatus());
        assertAll(
                () -> assertEquals(1, completeJob.getJobQueueBatchFiles().size(), String.format("Unexpected JobModel: %s", completeJob.toString())),
                () -> assertFalse(completeJob.getJobQueueFile(ResourceType.ExplanationOfBenefit).isPresent(), "Expect a EOB"),
                () -> assertTrue(completeJob.getJobQueueFile(ResourceType.OperationOutcome).isPresent(), "Expect an error"));

        // Look at the output files
        final var outputFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.ExplanationOfBenefit, 0);
        assertFalse(Files.exists(Path.of(outputFilePath)));
        final var errorFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.OperationOutcome, 0);
        assertTrue(Files.exists(Path.of(errorFilePath)), "expect error file for failed patient");
        String operationOutcome = Files.readString(Path.of(errorFilePath));
        OperationOutcome o = FhirContext.forDstu3().newJsonParser().parseResource(OperationOutcome.class, operationOutcome);
        assertEquals(NO_DATE_MATCH_DETAIL, o.getIssueFirstRep().getDetails().getText());
    }

    @Test
    void lookBackAllCriteriaMismatch() throws IOException {
        final var orgID = UUID.randomUUID();
        final var npi = NPIUtil.generateNPI();

        Mockito.doReturn(UUID.randomUUID().toString()).when(lookBackService).getPractitionerNPIFromRoster(Mockito.any(), Mockito.anyString(), Mockito.anyString());
        Mockito.doReturn(new LookBackAnswer(npi, npi, 1, new Date())
                .addEobBillingPeriod(DateUtils.addYears(new Date(), -1))
                .addEobOrganization(NPIUtil.generateNPI())
                .addEobProviders(List.of(NPIUtil.generateNPI()))).when(lookBackService).getLookBackAnswer(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyLong());

        // Make a simple job with one resource type
        final var jobID = queue.createJob(
                orgID,
                TEST_PROVIDER_ID,
                MockBlueButtonClient.TEST_PATIENT_MBIS.subList(0,1),
                Collections.singletonList(ResourceType.ExplanationOfBenefit),
                MockBlueButtonClient.TEST_LAST_UPDATED.minusSeconds(1),
                MockBlueButtonClient.BFD_TRANSACTION_TIME,
                null, true);

        // Do the job
        queue.claimBatch(engine.getAggregatorID())
                .ifPresent(engine::processJobBatch);

        // Look at the result
        final var completeJob = queue.getJobBatches(jobID).stream().findFirst().orElseThrow();
        assertEquals(JobStatus.COMPLETED, completeJob.getStatus());
        assertAll(
                () -> assertEquals(1, completeJob.getJobQueueBatchFiles().size(), String.format("Unexpected JobModel: %s", completeJob.toString())),
                () -> assertFalse(completeJob.getJobQueueFile(ResourceType.ExplanationOfBenefit).isPresent(), "Expect a EOB"),
                () -> assertTrue(completeJob.getJobQueueFile(ResourceType.OperationOutcome).isPresent(), "Expect an error"));

        // Look at the output files
        final var outputFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.ExplanationOfBenefit, 0);
        assertFalse(Files.exists(Path.of(outputFilePath)));
        final var errorFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.OperationOutcome, 0);
        assertTrue(Files.exists(Path.of(errorFilePath)), "expect error file for failed patient");
        String operationOutcome = Files.readString(Path.of(errorFilePath));
        OperationOutcome o = FhirContext.forDstu3().newJsonParser().parseResource(OperationOutcome.class, operationOutcome);
        assertEquals(NO_MATCHES_DETAIL, o.getIssueFirstRep().getDetails().getText());
    }

    @Test
    void lookBackNpiCriteriaMismatch() throws IOException {
        final var orgID = UUID.randomUUID();
        final var npi = NPIUtil.generateNPI();

        Mockito.doReturn(UUID.randomUUID().toString()).when(lookBackService).getPractitionerNPIFromRoster(Mockito.any(), Mockito.anyString(), Mockito.anyString());
        Mockito.doReturn(new LookBackAnswer(npi, npi, 1, new Date())
                .addEobBillingPeriod(new Date())
                .addEobOrganization(NPIUtil.generateNPI())
                .addEobProviders(List.of(NPIUtil.generateNPI()))).when(lookBackService).getLookBackAnswer(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyLong());

        // Make a simple job with one resource type
        final var jobID = queue.createJob(
                orgID,
                TEST_PROVIDER_ID,
                MockBlueButtonClient.TEST_PATIENT_MBIS.subList(0,1),
                Collections.singletonList(ResourceType.ExplanationOfBenefit),
                MockBlueButtonClient.TEST_LAST_UPDATED.minusSeconds(1),
                MockBlueButtonClient.BFD_TRANSACTION_TIME,
                null, true);

        // Do the job
        queue.claimBatch(engine.getAggregatorID())
                .ifPresent(engine::processJobBatch);

        // Look at the result
        final var completeJob = queue.getJobBatches(jobID).stream().findFirst().orElseThrow();
        assertEquals(JobStatus.COMPLETED, completeJob.getStatus());
        assertAll(
                () -> assertEquals(1, completeJob.getJobQueueBatchFiles().size(), String.format("Unexpected JobModel: %s", completeJob.toString())),
                () -> assertFalse(completeJob.getJobQueueFile(ResourceType.ExplanationOfBenefit).isPresent(), "Expect a EOB"),
                () -> assertTrue(completeJob.getJobQueueFile(ResourceType.OperationOutcome).isPresent(), "Expect an error"));

        // Look at the output files
        final var outputFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.ExplanationOfBenefit, 0);
        assertFalse(Files.exists(Path.of(outputFilePath)));
        final var errorFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.OperationOutcome, 0);
        assertTrue(Files.exists(Path.of(errorFilePath)), "expect error file for failed patient");
        String operationOutcome = Files.readString(Path.of(errorFilePath));
        OperationOutcome o = FhirContext.forDstu3().newJsonParser().parseResource(OperationOutcome.class, operationOutcome);
        assertEquals(NO_NPI_MATCH_DETAIL, o.getIssueFirstRep().getDetails().getText());
    }

    @Test
    void lookBackNpiCriteriaMatch() {
        final var orgID = UUID.randomUUID();
        final var npi = NPIUtil.generateNPI();

        Mockito.doReturn(UUID.randomUUID().toString()).when(lookBackService).getPractitionerNPIFromRoster(Mockito.any(), Mockito.anyString(), Mockito.anyString());
        Mockito.doReturn(new LookBackAnswer(npi, npi, 1, new Date())
                .addEobBillingPeriod(new Date())
                .addEobOrganization(npi)
                .addEobProviders(List.of(NPIUtil.generateNPI()))).when(lookBackService).getLookBackAnswer(Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyLong());

        // Make a simple job with one resource type
        final var jobID = queue.createJob(
                orgID,
                TEST_PROVIDER_ID,
                MockBlueButtonClient.TEST_PATIENT_MBIS.subList(0,1),
                Collections.singletonList(ResourceType.ExplanationOfBenefit),
                MockBlueButtonClient.TEST_LAST_UPDATED.minusSeconds(1),
                MockBlueButtonClient.BFD_TRANSACTION_TIME,
                null, true);

        // Do the job
        queue.claimBatch(engine.getAggregatorID())
                .ifPresent(engine::processJobBatch);

        // Look at the result
        final var completeJob = queue.getJobBatches(jobID).stream().findFirst().orElseThrow();
        assertEquals(JobStatus.COMPLETED, completeJob.getStatus());
        assertAll(
                () -> assertEquals(4, completeJob.getJobQueueBatchFiles().size(), String.format("Unexpected JobModel: %s", completeJob.toString())),
                () -> assertTrue(completeJob.getJobQueueFile(ResourceType.ExplanationOfBenefit).isPresent(), "Expect a EOB"),
                () -> assertFalse(completeJob.getJobQueueFile(ResourceType.OperationOutcome).isPresent(), "Expect an error"));

        // Look at the output files
        final var outputFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.ExplanationOfBenefit, 0);
        assertTrue(Files.exists(Path.of(outputFilePath)));
        final var errorFilePath = ResourceWriter.formOutputFilePath(exportPath, completeJob.getBatchID(), ResourceType.OperationOutcome, 0);
        assertFalse(Files.exists(Path.of(errorFilePath)), "expect error file for failed patient");
    }
}
