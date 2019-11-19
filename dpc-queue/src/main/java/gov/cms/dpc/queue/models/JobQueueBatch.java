package gov.cms.dpc.queue.models;

import gov.cms.dpc.common.converters.hibernate.StringListConverter;
import gov.cms.dpc.queue.JobStatus;
import gov.cms.dpc.queue.converters.ResourceTypeListConverter;
import gov.cms.dpc.queue.exceptions.JobQueueFailure;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.hl7.fhir.dstu3.model.ResourceType;

import javax.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * The JobQueueBatch tracks the work done on a bulk export request. It contains the essential details of the request and
 * the results of the requests. The job is mutated between QUEUED to RUNNING to COMPLETE or FAILED states.
 * <p>
 * For additional information on the design: https://confluenceent.cms.gov/display/DAPC/Queue+v2+Notes
 */
@Entity(name = "job_queue_batch")
public class JobQueueBatch implements Serializable {

    private static final long serialVersionUID = -578824686165779398L;

    /**
     * The list of resource type supported by DCP
     */
    public static final List<ResourceType> validResourceTypes = List.of(
            ResourceType.Patient,
            ResourceType.ExplanationOfBenefit,
            ResourceType.Coverage
    );

    /**
     * Test if the resource type is in the list of resources supported by the DCP
     *
     * @param type - {@code true} resource is supported by DPC. {@code false} resource is not supported by DPC.
     * @return True iff the passed in type is valid f
     */
    public static Boolean isValidResourceType(ResourceType type) {
        return validResourceTypes.contains(type);
    }

    /**
     * The unique batch identifier
     */
    @Id
    @Column(name = "batch_id")
    private UUID batchID;

    /**
     * The unique job identifier
     */
    @Column(name = "job_id")
    private UUID jobID;

    /**
     * The unique organization identifier
     */
    @Column(name = "organization_id")
    private UUID orgID;

    /**
     * The provider-id from the request
     */
    @Column(name = "provider_id")
    private String providerID;

    /**
     * The current status of this job
     */
    protected JobStatus status;

    /**
     * The priority of job (generated by static algorithm)
     */
    private Integer priority;

    /**
     * The list of patient-ids for the specified provider from the attribution server
     */
    @Convert(converter = StringListConverter.class)
    @Column(name = "patients", columnDefinition = "text")
    List<String> patients;

    /**
     * The last processed patient index. Null indicates no patients have been processed yet.
     */
    @Column(name = "patient_index")
    Integer patientIndex;

    /**
     * The list of resources for this job. Set at job creation.
     */
    @Convert(converter = ResourceTypeListConverter.class)
    @Column(name = "resource_types")
    private List<ResourceType> resourceTypes;

    /**
     * The current aggregator processing the batch. Null indicates no aggregator is processing the batch.
     */
    @Column(name = "aggregator_id")
    protected UUID aggregatorID;

    /**
     * The time the job was last processed
     */
    @Column(name = "update_time", nullable = true)
    protected OffsetDateTime updateTime;

    /**
     * The time the job was submitted
     */
    @Column(name = "submit_time", nullable = true)
    protected OffsetDateTime submitTime;

    /**
     * The time the job started to work
     */
    @Column(name = "start_time", nullable = true)
    protected OffsetDateTime startTime;

    /**
     * The time the job was completed
     */
    @Column(name = "complete_time", nullable = true)
    protected OffsetDateTime completeTime;

    /**
     * The list of job results
     * <p>
     * We need to use {@link FetchType#EAGER}, otherwise the session will close before we actually read the job results and the call will fail.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "batch_id")
    private List<JobQueueBatchFile> jobQueueBatchFiles;

    public JobQueueBatch() {
    }

    public JobQueueBatch(UUID jobID, UUID orgID, String providerID, List<String> patients, List<ResourceType> resourceTypes) {
        this.batchID = UUID.randomUUID();
        this.jobID = jobID;
        this.orgID = orgID;
        this.providerID = providerID;
        this.patients = patients;
        this.resourceTypes = resourceTypes;
        this.status = JobStatus.QUEUED;
        this.submitTime = OffsetDateTime.now(ZoneOffset.UTC);
        this.jobQueueBatchFiles = new ArrayList<>();
    }

    /**
     * Is the job model fields consistent. Useful before and after serialization.
     *
     * @return True if the fields are consistent with each other
     */
    public Boolean isValid() {
        switch (status) {
            case QUEUED:
                return submitTime != null && aggregatorID == null;
            case RUNNING:
                return submitTime != null && startTime != null && updateTime != null && aggregatorID != null;
            case COMPLETED:
            case FAILED:
                return submitTime != null && startTime != null && updateTime != null && completeTime != null && aggregatorID == null;
            default:
                return false;
        }
    }

    public UUID getBatchID() {
        return batchID;
    }

    public UUID getJobID() {
        return jobID;
    }

    public UUID getOrgID() {
        return orgID;
    }

    public String getProviderID() {
        return providerID;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Integer getPriority() {
        return priority;
    }

    public List<String> getPatients() {
        return patients;
    }

    public Optional<Integer> getPatientIndex() {
        return Optional.ofNullable(patientIndex);
    }

    public List<ResourceType> getResourceTypes() {
        return resourceTypes;
    }

    public Optional<UUID> getAggregatorID() {
        return Optional.ofNullable(aggregatorID);
    }

    public void setAggregatorIDForTesting(UUID aggregatorID) {
        this.aggregatorID = aggregatorID;
    }

    public Optional<OffsetDateTime> getUpdateTime() {
        return Optional.ofNullable(updateTime);
    }

    public Optional<OffsetDateTime> getSubmitTime() {
        return Optional.ofNullable(submitTime);
    }

    public Optional<OffsetDateTime> getStartTime() {
        return Optional.ofNullable(startTime);
    }

    public Optional<OffsetDateTime> getCompleteTime() {
        return Optional.ofNullable(completeTime);
    }

    public List<JobQueueBatchFile> getJobQueueBatchFiles() {
        return jobQueueBatchFiles;
    }

    public Optional<JobQueueBatchFile> getJobQueueFile(ResourceType forResourceType) {
        return jobQueueBatchFiles.stream().filter(result -> result.getResourceType().equals(forResourceType)).findFirst();
    }

    public Optional<JobQueueBatchFile> getJobQueueFile(String fileName) {
        return jobQueueBatchFiles.stream().filter(result -> result.getFileName().equals(fileName)).findFirst();
    }

    public synchronized Optional<JobQueueBatchFile> getJobQueueFileLatest(ResourceType forResourceType) {
        return jobQueueBatchFiles.stream()
                .filter(result -> result.getResourceType().equals(forResourceType))
                .max(Comparator.comparingInt(JobQueueBatchFile::getSequence));
    }

    public synchronized JobQueueBatchFile addJobQueueFile(ResourceType resourceType, int sequence, int batchSize) {
        Optional<JobQueueBatchFile> existingFile = this.getJobQueueFile(JobQueueBatchFile.formOutputFileName(batchID, resourceType, sequence));

        if (existingFile.isPresent()) {
            existingFile.get().appendCount(batchSize);
            return existingFile.get();
        } else {
            JobQueueBatchFile file = new JobQueueBatchFile(jobID, batchID, resourceType, sequence, batchSize);
            this.jobQueueBatchFiles.add(file);
            return file;
        }
    }

    /**
     * Transition this job to running status. This job should be in the QUEUED state.
     *
     * @param aggregatorID - the current aggregator working the job
     */
    public void setRunningStatus(UUID aggregatorID) {
        if (this.status != JobStatus.QUEUED) {
            throw new JobQueueFailure(jobID, batchID, String.format("Cannot run job. JobStatus: %s", this.status));
        }
        this.verifyAggregatorID(aggregatorID);
        status = JobStatus.RUNNING;
        this.aggregatorID = aggregatorID;
        startTime = OffsetDateTime.now(ZoneOffset.UTC);
        this.setUpdateTime();
    }

    /**
     * Fetch the next patient in the batch and increment the patient index.
     * Returns null if at the end of the list.
     *
     * @param aggregatorID - the current aggregator working the job
     * @return - Next patient to process, if one exists
     */
    public Optional<String> fetchNextPatient(UUID aggregatorID) {
        if (this.status != JobStatus.RUNNING) {
            throw new JobQueueFailure(jobID, batchID, String.format("Cannot fetch next batch. JobStatus: %s", this.status));
        }
        this.verifyAggregatorID(aggregatorID);
        this.setUpdateTime();
        int index = this.getPatientIndex().orElse(-1) + 1;
        if (index < this.patients.size()) {
            // Patient index should be set to the last successful fetched result
            this.patientIndex = index;
            return Optional.of(this.patients.get(this.patientIndex));
        }
        return Optional.empty();
    }

    /**
     * Pauses the current batch and allows another aggregator to pickup where left off
     *
     * @param aggregatorID - the current aggregator working the job
     */
    public void setPausedStatus(UUID aggregatorID) {
        if (this.status != JobStatus.RUNNING) {
            throw new JobQueueFailure(jobID, batchID, String.format("Cannot pause batch. JobStatus: %s", this.status));
        }
        this.verifyAggregatorID(aggregatorID);
        this.status = JobStatus.QUEUED;
        this.aggregatorID = null;

        this.setUpdateTime();
    }

    /**
     * Sets the completed status and verifies can be completed.
     *
     * @param aggregatorID - the current aggregator working the job
     */
    public void setCompletedStatus(UUID aggregatorID) {
        if (this.status != JobStatus.RUNNING) {
            throw new JobQueueFailure(jobID, batchID, String.format("Cannot complete. JobStatus: %s", this.status));
        }
        if (!this.patients.isEmpty() && (this.patientIndex == null || this.getPatients().size() != this.patientIndex + 1)) {
            throw new JobQueueFailure(jobID, batchID, String.format("Cannot complete. Job processing not finished. Only on patient %d of %d", this.getPatientIndex().orElse(-1) + 1, patients.size()));
        }
        this.verifyAggregatorID(aggregatorID);
        this.status = JobStatus.COMPLETED;
        this.aggregatorID = null;
        this.patientIndex = null;
        completeTime = OffsetDateTime.now(ZoneOffset.UTC);

        this.setUpdateTime();
    }

    /**
     * Marks the job batch as failed.
     *
     * @param aggregatorID - the current aggregator working the job
     */
    public void setFailedStatus(UUID aggregatorID) {
        this.status = JobStatus.FAILED;
        this.aggregatorID = null;
        completeTime = OffsetDateTime.now(ZoneOffset.UTC);
        this.getJobQueueBatchFiles().clear();

        this.setUpdateTime();
    }

    /**
     * Restarts the batch so it can be freshly picked up by a new aggregator.
     */
    public void restartBatch() {
        this.status = JobStatus.QUEUED;
        this.patientIndex = null;
        this.startTime = null;
        this.completeTime = null;
        this.aggregatorID = null;
        this.getJobQueueBatchFiles().clear();

        this.setUpdateTime();
    }

    /**
     * Set the job priority.
     *
     * @param priority - job priority
     */
    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    /**
     * Verifies that the aggregator is still assigned to the batch, if not throw exception
     *
     * @param aggregatorID - the current aggregator working the job
     */
    protected void verifyAggregatorID(UUID aggregatorID) throws JobQueueFailure {
        if (this.aggregatorID != null && !this.aggregatorID.equals(aggregatorID)) {
            throw new JobQueueFailure(jobID, batchID, String.format("Cannot update job. Cannot process a job owned by another aggregator. Existing Aggregator: %s Aggregator Claiming: %s", this.aggregatorID, aggregatorID));
        }
    }

    /**
     * Keep the update time in sync whenever a change occurs after the start time
     */
    public void setUpdateTime() {
        if (startTime != null) {
            updateTime = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (!(o instanceof JobQueueBatch)) return false;

        JobQueueBatch that = (JobQueueBatch) o;

        return new EqualsBuilder()
                .append(batchID, that.batchID)
                .append(jobID, that.jobID)
                .append(orgID, that.orgID)
                .append(providerID, that.providerID)
                .append(status, that.status)
                .append(priority, that.priority)
                .append(patients, that.patients)
                .append(patientIndex, that.patientIndex)
                .append(resourceTypes, that.resourceTypes)
                .append(aggregatorID, that.aggregatorID)
                .append(updateTime, that.updateTime)
                .append(submitTime, that.submitTime)
                .append(startTime, that.startTime)
                .append(completeTime, that.completeTime)
                .append(jobQueueBatchFiles, that.jobQueueBatchFiles)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37)
                .append(batchID)
                .append(jobID)
                .append(orgID)
                .append(providerID)
                .append(status)
                .append(priority)
                .append(patients)
                .append(patientIndex)
                .append(resourceTypes)
                .append(aggregatorID)
                .append(updateTime)
                .append(submitTime)
                .append(startTime)
                .append(completeTime)
                .toHashCode();
    }

    @Override
    public String toString() {
        return "JobQueueBatch{" +
                "batchID=" + batchID +
                ", jobID=" + jobID +
                ", orgID=" + orgID +
                ", providerID='" + providerID + '\'' +
                ", status=" + status +
                ", priority=" + priority +
                ", patients=" + patients +
                ", patientIndex=" + patientIndex +
                ", resourceTypes=" + resourceTypes +
                ", aggregatorID=" + aggregatorID +
                ", updateTime=" + updateTime +
                ", submitTime=" + submitTime +
                ", startTime=" + startTime +
                ", completeTime=" + completeTime +
                '}';
    }
}
