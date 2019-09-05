package gov.cms.dpc.api.resources;

import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IReadExecutable;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import gov.cms.dpc.api.APITestHelpers;
import gov.cms.dpc.api.AbstractSecureApplicationTest;
import gov.cms.dpc.fhir.DPCIdentifierSystem;
import gov.cms.dpc.fhir.helpers.FHIRHelpers;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Patient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Date;

import static gov.cms.dpc.api.APITestHelpers.ATTRIBUTION_URL;
import static gov.cms.dpc.api.APITestHelpers.ORGANIZATION_ID;
import static org.junit.jupiter.api.Assertions.*;

@Disabled
class PatientResourceTest extends AbstractSecureApplicationTest {

    PatientResourceTest() {
        // Not used
    }

    @Test
    void ensurePatientsExist() throws IOException {
        final IParser parser = ctx.newJsonParser();
        final IGenericClient attrClient = APITestHelpers.buildAttributionClient(ctx);
        final IGenericClient client = APITestHelpers.buildAuthenticatedClient(ctx, getBaseURL(), ORGANIZATION_TOKEN);
        APITestHelpers.setupPatientTest(client, parser);

        final Bundle patients = client
                .search()
                .forResource(Patient.class)
                .encodedJson()
                .returnBundle(Bundle.class)
                .execute();

        assertEquals(100, patients.getTotal(), "Should have correct number of patients");

        final Bundle specificSearch = client
                .search()
                .forResource(Patient.class)
                .where(Patient.IDENTIFIER.exactly().systemAndCode(DPCIdentifierSystem.MBI.getSystem(), "19990000002901"))
                .returnBundle(Bundle.class)
                .encodedJson()
                .execute();

        assertEquals(1, specificSearch.getTotal(), "Should have a single patient");

        // Fetch the provider directly
        final Patient foundPatient = (Patient) specificSearch.getEntryFirstRep().getResource();

        final Patient queriedProvider = client
                .read()
                .resource(Patient.class)
                .withId(foundPatient.getIdElement())
                .encodedJson()
                .execute();

        assertTrue(foundPatient.equalsDeep(queriedProvider), "Search and GET should be identical");

        // Create a new org and make sure it has no providers
        final String m2 = FHIRHelpers.registerOrganization(attrClient, parser, OTHER_ORG_ID, ATTRIBUTION_URL);

        // Update the Macaroons interceptor to use the new Organization token
        ((APITestHelpers.MacaroonsInterceptor) client.getInterceptorService().getAllRegisteredInterceptors().get(0)).setMacaroon(m2);

        final Bundle otherPatients = client
                .search()
                .forResource(Patient.class)
                .returnBundle(Bundle.class)
                .encodedJson()
                .execute();

        assertEquals(0, otherPatients.getTotal(), "Should not have any practitioners");

        // Try to look for one of the other patients
        final IReadExecutable<Patient> fetchRequest = client
                .read()
                .resource(Patient.class)
                .withId(foundPatient.getId())
                .encodedJson();

        assertThrows(AuthenticationException.class, fetchRequest::execute, "Should not be authorized");

        // Search, and find nothing
        final Bundle otherSpecificSearch = client
                .search()
                .forResource(Patient.class)
                .where(Patient.IDENTIFIER.exactly().identifier(foundPatient.getIdentifierFirstRep().getValue()))
                .returnBundle(Bundle.class)
                .encodedJson()
                .execute();

        assertEquals(0, otherSpecificSearch.getTotal(), "Should have a specific provider");
    }

    @Test
    void testPatientRemoval() throws IOException {
        final IParser parser = ctx.newJsonParser();
        final IGenericClient attrClient = APITestHelpers.buildAttributionClient(ctx);
        final String macaroon = FHIRHelpers.registerOrganization(attrClient, parser, ORGANIZATION_ID, ATTRIBUTION_URL);
        final IGenericClient client = APITestHelpers.buildAuthenticatedClient(ctx, getBaseURL(), macaroon);

        final Bundle patients = client
                .search()
                .forResource(Patient.class)
                .encodedJson()
                .returnBundle(Bundle.class)
                .execute();

        assertEquals(100, patients.getTotal(), "Should have correct number of patients");

        // Try to remove one

        final Patient patient = (Patient) patients.getEntry().get(patients.getTotal() - 2).getResource();

        client
                .delete()
                .resource(patient)
                .encodedJson()
                .execute();

        // Make sure it's done

        final IReadExecutable<Patient> fetchRequest = client
                .read()
                .resource(Patient.class)
                .withId(patient.getId())
                .encodedJson();

        // TODO: DPC-433, this really should be NotFound, but we can't disambiguate between the two cases
        assertThrows(AuthenticationException.class, fetchRequest::execute, "Should not have found the resource");

        // Search again
        final Bundle secondSearch = client
                .search()
                .forResource(Patient.class)
                .encodedJson()
                .returnBundle(Bundle.class)
                .execute();

        assertEquals(99, secondSearch.getTotal(), "Should have correct number of patients");
    }

    @Test
    void testPatientUpdating() throws IOException {
        final IParser parser = ctx.newJsonParser();
        final IGenericClient attrClient = APITestHelpers.buildAttributionClient(ctx);
        final String macaroon = FHIRHelpers.registerOrganization(attrClient, parser, ORGANIZATION_ID, ATTRIBUTION_URL);
        final IGenericClient client = APITestHelpers.buildAuthenticatedClient(ctx, getBaseURL(), macaroon);

        final Bundle patients = client
                .search()
                .forResource(Patient.class)
                .encodedJson()
                .returnBundle(Bundle.class)
                .execute();

        assertEquals(99, patients.getTotal(), "Should have correct number of patients");

        // Try to remove one

        final Patient patient = (Patient) patients.getEntry().get(patients.getTotal() - 2).getResource();
        patient.setBirthDate(Date.valueOf("2000-01-01"));

        final MethodOutcome outcome = client
                .update()
                .resource(patient)
                .withId(patient.getId())
                .encodedJson()
                .execute();

        assertTrue(((Patient) outcome.getResource()).equalsDeep(patient), "Should have been updated correctly");
    }
}
