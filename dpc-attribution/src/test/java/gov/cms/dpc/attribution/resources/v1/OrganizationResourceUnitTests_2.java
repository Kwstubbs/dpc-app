package gov.cms.dpc.attribution.resources.v1;

import gov.cms.dpc.attribution.DPCAttributionConfiguration;
import gov.cms.dpc.attribution.jdbi.EndpointDAO;
import gov.cms.dpc.attribution.jdbi.OrganizationDAO;
import gov.cms.dpc.common.entities.AddressEntity;
import gov.cms.dpc.common.entities.ContactEntity;
import gov.cms.dpc.common.entities.OrganizationEntity;
import gov.cms.dpc.fhir.DPCIdentifierSystem;
import gov.cms.dpc.fhir.converters.FHIREntityConverter;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.dstu3.model.Endpoint;
import org.hl7.fhir.dstu3.model.Organization;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.UuidType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockitoAnnotations;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(DropwizardExtensionsSupport.class)
public class OrganizationResourceUnitTests_2 {
    private static final EndpointDAO endpointDAO = mock(EndpointDAO.class);
    private static final FHIREntityConverter converter = mock(FHIREntityConverter.class);
    private static final DPCAttributionConfiguration config = mock(DPCAttributionConfiguration.class);
    private static final OrganizationDAO mockDao = mock(OrganizationDAO.class);
    private static final ResourceExtension EXT = ResourceExtension.builder()
            .addResource(new OrganizationResource(converter, mockDao, endpointDAO, config))
            .build();
//    private Entity<String> _uuid;
    private OrganizationEntity originalOrgEntity;
    private Organization orginalOrg;
    private OrganizationEntity updatedOrgEntity;
    private Organization updatedOrg;
    private String stringUuid;
    private UUID uuid;
    private String npi;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        stringUuid = "6ae08851-b5c6-40e3-8df2-e21fc37a7a8d";
        uuid = UUID.fromString(stringUuid);
        npi = "8271216870";
        originalOrgEntity = createTestOrgEntity();
        orginalOrg = createTestOrg();
        updatedOrgEntity = createUpdatedTestOrgEntity();
        updatedOrg = createUpdatedTestOrg();
//        _uuid = new Entity<String>(stringUuid, new MediaType().withCharset(stringUuid));
    }

    @Test
    void getOrgDropwizardDocs() {
        UUID id = uuid;
        when(mockDao.fetchOrganization(id)).thenReturn(Optional.of(originalOrgEntity));
        when(converter.toFHIR(Organization.class, originalOrgEntity)).thenReturn(orginalOrg);
        String path = "/Organization/" + id;

        Organization found = EXT.target(path).request().get(Organization.class);

        assertThat(found.getId()).isEqualTo(orginalOrg.getId());
        verify(mockDao).fetchOrganization(id);
    }

//    @Test
//    void updateOrg() {
//        UUID id = uuid;
//        Organization originalOrg = createTestOrg();
//        OrganizationEntity originalOrgEntity = createTestOrgEntity();
//        OrganizationEntity updatedOrgEntity = createUpdatedTestOrgEntity();
//
//        when(converter.fromFHIR(OrganizationEntity.class, originalOrg)).thenReturn(originalOrgEntity);
//        when(mockDao.fetchOrganization(id)).thenReturn(Optional.of(originalOrgEntity));
//        when(converter.toFHIR(Organization.class, originalOrgEntity)).thenReturn(orginalOrg);
//        when(mockDao.updateOrganization(id, updatedOrgEntity)).thenReturn(updatedOrgEntity);
//        when(converter.toFHIR(Organization.class, updatedOrgEntity)).thenReturn(updatedOrg);
//        String path = "/Organization/" + id;
//
//        Response response = EXT.target(path).request().put(_uuid, Organization.class);
//
//
//    }

    private Organization createTestOrg() {
        Organization org = new Organization();
        org.setId(stringUuid);
        org.addIdentifier().setSystem(DPCIdentifierSystem.NPPES.getSystem()).setValue(npi);
        org.setName("Test Org");
        org.addAddress().addLine("12345 Fake Street");
        org.addEndpoint(new Reference(new Endpoint()));
        return org;
    }

    private OrganizationEntity createTestOrgEntity() {
        OrganizationEntity org = new OrganizationEntity();
        org.setId(uuid);
        org.setOrganizationID(createTestOrgId());
        org.setOrganizationName("Test Org");
        org.setOrganizationAddress(createTestAddressEntity());
        ArrayList eps = new ArrayList<>();
        org.setEndpoints(eps);
//        var contacts = new ArrayList<ContactEntity>();
//        org.setContacts(contacts);
        return org;
    }

    private OrganizationEntity.OrganizationID createTestOrgId() {
        OrganizationEntity.OrganizationID id = new OrganizationEntity.OrganizationID();
        id.setValue(npi);
        id.setSystem(DPCIdentifierSystem.NPPES);
        return id;
    }

    private AddressEntity createTestAddressEntity() {
        AddressEntity address = new AddressEntity();
        address.setLine1("12345 Fake Street");
        return address;
    }

    private Organization createUpdatedTestOrg() {
        Organization org = new Organization();
        org.setId(stringUuid);
        org.addIdentifier().setSystem(DPCIdentifierSystem.NPPES.getSystem()).setValue(npi);
        org.setName("Test Org");
        org.addAddress().addLine("54321 Real Street");
        org.addEndpoint(new Reference(new Endpoint()));
        return org;
    }

    private OrganizationEntity createUpdatedTestOrgEntity() {
        OrganizationEntity org = new OrganizationEntity();
        org.setId(uuid);
        org.setOrganizationID(createUpdatedTestOrgId());
        org.setOrganizationName("Test Org");
        org.setOrganizationAddress(createUpdatedTestAddressEntity());
        ArrayList eps = new ArrayList<>();
        org.setEndpoints(eps);
        var contacts = new ArrayList<ContactEntity>();
        org.setContacts(contacts);
        return org;
    }

    private OrganizationEntity.OrganizationID createUpdatedTestOrgId() {
        OrganizationEntity.OrganizationID id = new OrganizationEntity.OrganizationID();
        id.setValue(stringUuid);
        id.setSystem(DPCIdentifierSystem.NPPES);
        return id;
    }

    private AddressEntity createUpdatedTestAddressEntity() {
        AddressEntity address = new AddressEntity();
        address.setLine1("54321 Real Street");
        return address;
    }
}
