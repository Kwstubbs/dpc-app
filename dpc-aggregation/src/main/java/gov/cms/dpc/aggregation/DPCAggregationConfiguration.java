package gov.cms.dpc.aggregation;

import ca.mestevens.java.configuration.TypesafeConfiguration;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.typesafe.config.ConfigRenderOptions;
import gov.cms.dpc.aggregation.bbclient.BBClientConfiguration;
import gov.cms.dpc.common.hibernate.IDPCDatabase;
import gov.cms.dpc.queue.DPCQueueConfig;
import io.dropwizard.db.DataSourceFactory;
import org.hibernate.validator.constraints.NotEmpty;
import org.redisson.config.Config;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.io.IOException;

public class DPCAggregationConfiguration extends TypesafeConfiguration implements IDPCDatabase, DPCQueueConfig {

    @Valid
    @NotNull
    @JsonProperty("database")
    private DataSourceFactory database = new DataSourceFactory();

    @Valid
    @NotNull
    @JsonProperty("bbclient")
    private BBClientConfiguration clientConfiguration = new BBClientConfiguration();

    @NotEmpty
    private String exportPath;

    public BBClientConfiguration getClientConfiguration() {
        return clientConfiguration;
    }

    @Override
    public DataSourceFactory getDatabase() {
        return this.database;
    }

    public String getExportPath() {
        return exportPath;
    }

    public void setExportPath(String exportPath) {
        this.exportPath = exportPath;
    }

    @Override
    public Config getQueueConfig() {
        final String configString = getConfig().getConfig("queue").root().render(ConfigRenderOptions.concise());
        try {
            return Config.fromJSON(configString);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read queue config.", e);
        }
    }
}
