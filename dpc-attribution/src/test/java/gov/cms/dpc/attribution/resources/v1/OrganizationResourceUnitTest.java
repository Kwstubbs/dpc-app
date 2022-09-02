package gov.cms.dpc.attribution.resources.v1;

import gov.cms.dpc.attribution.AttributionTestHelpers;
import gov.cms.dpc.attribution.DPCAttributionConfiguration;
import gov.cms.dpc.attribution.jdbi.EndpointDAO;
import gov.cms.dpc.attribution.jdbi.OrganizationDAO;
import gov.cms.dpc.common.entities.AddressEntity;
import gov.cms.dpc.common.entities.OrganizationEntity;
import gov.cms.dpc.fhir.DPCIdentifierSystem;
import gov.cms.dpc.fhir.converters.FHIREntityConverter;
import org.hl7.fhir.dstu3.model.*;
import org.hl7.fhir.dstu3.model.codesystems.EndpointConnectionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import javax.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

public class OrganizationResourceUnitTest {

    @Mock
    OrganizationDAO mockOrganizationDao;

    @Mock
    EndpointDAO mockEndpointDao;

    @Mock
    Supplier<UUID> uuidSupplier;

    private DPCAttributionConfiguration configuration;

    private FHIREntityConverter converter = FHIREntityConverter.initialize();

    private OrganizationResource resource;

    private String lookbackExemptOrgUuid = "0ab352f1-2bf1-44c4-aa7a-3004a1ffef12";

    private String testNpi = "1334567892";

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        configuration = new DPCAttributionConfiguration();
        resource = new OrganizationResource(converter,mockOrganizationDao,mockEndpointDao, configuration);
    }

    @Test
    void submitTestOrganizationAndNoLookbackExemptions() {
        Mockito.when(mockOrganizationDao.registerOrganization(any())).thenAnswer(invocation -> invocation.getArguments()[0]);
        configuration.setLookBackExemptOrgs(null);
        final Bundle bundle = buildBundleWithTestOrg(lookbackExemptOrgUuid);

        Response response = resource.submitOrganization(bundle);
        Organization orgCreated = (Organization) response.getEntity();
        assertEquals(201, response.getStatus(), "Should have returned a 200 status");
        assertEquals(lookbackExemptOrgUuid, orgCreated.getId(), "UUID passed in should have been used");
    }

    @Test
    void submitTestOrganizationWithLookbackExemptions() {
        Mockito.when(mockOrganizationDao.registerOrganization(any())).thenAnswer(invocation -> invocation.getArguments()[0]);
        configuration.setLookBackExemptOrgs(List.of(lookbackExemptOrgUuid));
        final Bundle bundle = buildBundleWithTestOrg(lookbackExemptOrgUuid);

        Response response = resource.submitOrganization(bundle);
        Organization orgCreated = (Organization) response.getEntity();
        assertEquals(201, response.getStatus(), "Should have returned a 200 status");
        assertEquals(lookbackExemptOrgUuid, orgCreated.getId(), "UUID passed in should have been used");
    }

    @Test
    void submitOrganizationWithIdSpecified() {
        Mockito.when(mockOrganizationDao.registerOrganization(any())).thenAnswer(invocation -> invocation.getArguments()[0]);
        configuration.setLookBackExemptOrgs(List.of(lookbackExemptOrgUuid));
        UUID uuid = UUID.randomUUID();
        final Bundle bundle = buildBundleWithTestOrg(uuid.toString());

        Response response = resource.submitOrganization(bundle);

        Organization orgCreated = (Organization) response.getEntity();
        assertEquals(201, response.getStatus(), "Should have returned a 200 status");
        assertEquals(uuid.toString(), orgCreated.getId(), "UUID passed in should have been used");
    }

    @Test
    void submitOrganizationWithNoIdSpecified() throws IllegalAccessException, NoSuchFieldException {
        Mockito.when(mockOrganizationDao.registerOrganization(any())).thenAnswer(invocation -> invocation.getArguments()[0]);
        configuration.setLookBackExemptOrgs(List.of(lookbackExemptOrgUuid));
        Field supplierField = OrganizationResource.class.getDeclaredField("uuidSupplier");
        supplierField.setAccessible(true);
        supplierField.set(resource, uuidSupplier);
        String validUUID = "df62c0c9-44df-476d-85fc-555c075fbb61"; //simulate valid random UUID that is not exempt;
        Mockito.when(uuidSupplier.get()).thenReturn(UUID.fromString(lookbackExemptOrgUuid), UUID.fromString(validUUID)); //Simulate generating a prohibited org id the first time, but not the second.

        final Bundle bundle = buildBundleWithTestOrg(null);

        Response response = resource.submitOrganization(bundle);
        Organization orgCreated = (Organization) response.getEntity();
        assertEquals(201, response.getStatus(), "Should have returned a 200 status");
        assertEquals(validUUID, orgCreated.getId(), "Should have use the UUID that was generated second, first one was prohibited.");
    }

    @Test
    void testUpdateOrganization() {
//        final Organization originalOrg = generateFakeOrganization();

//        AttributionTestHelpers.createOrgResource(lookbackExemptOrgId, testUUID);
//        List endpoints = new ArrayList<Endpoint>();
//        endpoints.add(createFakeEndpoint());
//        originalOrg.setEndpoint(endpoints);

//        OrganizationEntity stuff = converter.fromFHIR(OrganizationEntity.class, originalOrg);

        var originalOrg = createTestOrgEntity();

        Mockito.when(mockOrganizationDao.fetchOrganization(any())).thenReturn(Optional.ofNullable(originalOrg));

        final Organization updatedOrg = new Organization();
        updatedOrg.setId(lookbackExemptOrgUuid);
        updatedOrg.addIdentifier().setSystem(DPCIdentifierSystem.NPPES.getSystem()).setValue(testNpi);
        updatedOrg.setName("Test Org");
        updatedOrg.addAddress().addLine("54321 Real Street");

        OrganizationEntity updatedOrgEntity = createUpdateTestOrgEntity();

        Mockito.when(mockOrganizationDao.updateOrganization(UUID.fromString(lookbackExemptOrgUuid), updatedOrgEntity)).thenReturn(updatedOrgEntity);

        Response updateResponse = resource.updateOrganization(UUID.fromString(lookbackExemptOrgUuid), updatedOrg);
        Organization updatedOrganization = (Organization) updateResponse.getEntity();

        assertEquals(200, updateResponse.getStatus(), "Should have returned a 200 status");
    }


    private Bundle buildBundleWithTestOrg(String uuid){
        Organization organization = AttributionTestHelpers.createOrgResource(uuid, testNpi);
        return AttributionTestHelpers.createBundle(organization);
    }

    private Endpoint createFakeEndpoint() {
        final Endpoint endpoint = new Endpoint();

        // Payload type concept
        final CodeableConcept payloadType = new CodeableConcept();
        payloadType.addCoding().setCode("nothing").setSystem("http://nothing.com");

        endpoint.setPayloadType(List.of(payloadType));

        endpoint.setId("test-endpoint");
        endpoint.setConnectionType(new Coding(EndpointConnectionType.HL7FHIRREST.getSystem(), EndpointConnectionType.HL7FHIRREST.toCode(), EndpointConnectionType.HL7FHIRREST.getDisplay()));
        endpoint.setStatus(Endpoint.EndpointStatus.ACTIVE);
        return endpoint;
    }

    private Organization generateFakeOrganization() {
        final Organization organization = new Organization();
        organization.addEndpoint(new Reference("Endpoint/test-endpoint"));

        organization.setId("test-organization");
        organization.setName("Test Organization");

        return organization;
    }

    private OrganizationEntity.OrganizationID createTestOrgId() {
        OrganizationEntity.OrganizationID id = new OrganizationEntity.OrganizationID();
        id.setValue(testNpi);
        return id;
    }

    private OrganizationEntity createTestOrgEntity() {
        OrganizationEntity org = new OrganizationEntity();
        org.setId(UUID.fromString(lookbackExemptOrgUuid));
        org.setOrganizationID(createTestOrgId());
        org.setOrganizationName("Test Org");
        org.setOrganizationAddress(createTestAddressEntity());
        return org;
    }

    private AddressEntity createTestAddressEntity() {
        AddressEntity address = new AddressEntity();
        address.setLine1("12345 Fake Street");
        return address;
    }

    private OrganizationEntity createUpdateTestOrgEntity() {
        OrganizationEntity org = new OrganizationEntity();
        org.setId(UUID.fromString(lookbackExemptOrgUuid));
        org.setOrganizationID(createTestOrgId());
        org.setOrganizationName("Test Org");
        org.setOrganizationAddress(createUpdateTestAddressEntity());
        return org;
    }

    private AddressEntity createUpdateTestAddressEntity() {
        AddressEntity address = new AddressEntity();
        address.setLine1("54321 Real Street");
        return address;
    }
}