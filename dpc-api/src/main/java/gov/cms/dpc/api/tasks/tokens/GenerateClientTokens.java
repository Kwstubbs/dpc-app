package gov.cms.dpc.api.tasks.tokens;

import com.github.nitram509.jmacaroons.Macaroon;
import com.github.nitram509.jmacaroons.MacaroonVersion;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMultimap;
import gov.cms.dpc.api.auth.OrganizationPrincipal;
import gov.cms.dpc.api.entities.TokenEntity;
import gov.cms.dpc.api.resources.v1.TokenResource;
import gov.cms.dpc.api.resources.v1.OrganizationResource;
import gov.cms.dpc.macaroons.MacaroonBakery;
import io.dropwizard.jersey.jsr310.OffsetDateTimeParam;
import io.dropwizard.servlets.tasks.Task;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.dstu3.model.Organization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Optional;

/**
 * Admin task for creating a Golden macaroon which has superuser permissions in the application.
 * This should only ever be called once per environment.
 */
@Singleton
public class GenerateClientTokens extends Task {

    private static final Logger logger = LoggerFactory.getLogger(GenerateClientTokens.class);

    private final MacaroonBakery bakery;
    private final TokenResource tokenResource;
    private final OrganizationResource orgResource;

    @Inject
    public GenerateClientTokens(MacaroonBakery bakery, TokenResource tokenResource, OrganizationResource orgResource) {
        super("generate-token");
        this.bakery = bakery;
        this.tokenResource = tokenResource;
        this.orgResource = orgResource;
    }

    @Override
    public void execute(ImmutableMultimap<String, String> parameters, PrintWriter output) {
        final ImmutableCollection<String> expirationCollection = parameters.get("expiration");
        final ImmutableCollection<String> labelCollection = parameters.get("label");
        final ImmutableCollection<String> organizationCollection = parameters.get("organization");
        final ImmutableCollection<String> needCheckOrgCollection = parameters.get("checkOrg");
        if (organizationCollection.isEmpty()) {
            // Is this really want we want to be doing here, creating an unrestricted macaroon?
            createUnrestrictedMacaroon(output);
        } else {
            final String organizationId = organizationCollection.asList().get(0);
            final Organization organization = new Organization();
            organization.setId(organizationId);

            // `checkOrg` parameter is meant to be optional, if not present, default `canCreateToken` to true.
            // When `checkOrg` is provided, ensure the submitted orgId matches an org in the db before creating token.
            Boolean canCreateToken = true;
            final Boolean needOrgValidation = !needCheckOrgCollection.isEmpty()
                    && !StringUtils.isBlank(needCheckOrgCollection.asList().get(0))
                    && needCheckOrgCollection.asList().get(0) == "true";
            if(needOrgValidation) {
                canCreateToken = validateOrgExists(organization);
            }

            if(canCreateToken) {
                createTokan(output, labelCollection, expirationCollection, organization);
            } else {
                logger.warn("ATTEMPT TO CREATE ORPHAN MACAROON.");
                throw new WebApplicationException(String.format("ERROR: Organization not found with ID: \"%s\". Please double check your data and try again.", organizationId), Response.Status.BAD_REQUEST);
            }
        }
    }

    private void createUnrestrictedMacaroon(PrintWriter output) {
        logger.warn("CREATING UNRESTRICTED MACAROON. ENSURE THIS IS OK");
        final Macaroon macaroon = bakery.createMacaroon(Collections.emptyList());
        output.write(macaroon.serialize(MacaroonVersion.SerializationVersion.V1_BINARY));
    }

    private Boolean validateOrgExists(Organization organization) {
        final OrganizationPrincipal orgPrincipal = new OrganizationPrincipal(organization);
        final var existingOrg = this.orgResource.orgSearch(orgPrincipal);
        return existingOrg == null ? false : true;
    }

    private void createTokan(PrintWriter output, ImmutableCollection<String> labelCollection, ImmutableCollection<String> expirationCollection, Organization organization) {
        final String tokenLabel = labelCollection.isEmpty() ? null : labelCollection.asList().get(0);
        // Use Expiration Date if provided, otherwise use `empty` value, and will default to 1-year
        Optional<OffsetDateTimeParam> expiration = Optional.empty();
        final Boolean hasExpiration = !expirationCollection.isEmpty() && !StringUtils.isBlank(expirationCollection.asList().get(0));
        if(hasExpiration){
            expiration = Optional.of(new OffsetDateTimeParam(expirationCollection.asList().get(0)));
        }
        final TokenEntity tokenResponse = this.tokenResource
                .createOrganizationToken(
                        new OrganizationPrincipal(organization), null,
                        tokenLabel,
                        expiration);

        output.write(tokenResponse.getToken());
    }
}