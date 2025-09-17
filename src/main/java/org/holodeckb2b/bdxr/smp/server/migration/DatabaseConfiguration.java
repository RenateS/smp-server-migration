/*
 * Copyright (C) 2025 The Holodeck B2B Team, Sander Fieten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as 
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.holodeckb2b.bdxr.smp.server.migration;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.jpa.HibernatePersistenceProvider;

import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;

/**
 * Provides the JPA persistence info configuration to connect to the 3.0.x versions of the SMP server database. 
 *
 * @author Sander Fieten (sander at holodeck-b2b.org)
 */
final class DatabaseConfiguration implements PersistenceUnitInfo {

	public static final DatabaseConfiguration VERSION_3_CFG = new DatabaseConfiguration();
		
    @Override
    public String getPersistenceUnitName() {
        return "hb2b-smp-3.0.0";
    }

    @Override
    public String getPersistenceProviderClassName() {
        return HibernatePersistenceProvider.class.getName();
    }

    @Override
    public PersistenceUnitTransactionType getTransactionType() {
        return PersistenceUnitTransactionType.RESOURCE_LOCAL;
    }

    @Override
    public List<String> getManagedClassNames() {
        return List.of(
    			"org.holodeckb2b.bdxr.smp.server.db.entities.EndpointEntity",
    			"org.holodeckb2b.bdxr.smp.server.db.entities.IDSchemeEntity",
    			"org.holodeckb2b.bdxr.smp.server.db.entities.ParticipantEntity",
    			"org.holodeckb2b.bdxr.smp.server.db.entities.ProcessEntity",
    			"org.holodeckb2b.bdxr.smp.server.db.entities.ProcessGroupEntity",
    			"org.holodeckb2b.bdxr.smp.server.db.entities.ProcessInfoEntity",
    			"org.holodeckb2b.bdxr.smp.server.db.entities.ServerConfigEntity",
    			"org.holodeckb2b.bdxr.smp.server.db.entities.ServiceEntity",
    			"org.holodeckb2b.bdxr.smp.server.db.entities.ServiceMetadataTemplateEntity",
    			"org.holodeckb2b.bdxr.smp.server.db.entities.TransportProfileEntity",
    			"org.holodeckb2b.bdxr.smp.server.db.entities.AuditLogEntity",
    			"org.holodeckb2b.bdxr.smp.server.ui.auth.UserAccount"
    			);    	
    }

    @Override
    public Properties getProperties() {
        Properties props = new Properties();
        props.put(org.hibernate.cfg.AvailableSettings.HBM2DDL_AUTO, org.hibernate.tool.schema.Action.CREATE_ONLY);
        props.put(org.hibernate.cfg.AvailableSettings.SHOW_SQL, false);
        props.put(org.hibernate.cfg.AvailableSettings.QUERY_STARTUP_CHECKING, false);
        props.put(org.hibernate.cfg.AvailableSettings.GENERATE_STATISTICS, false);
        props.put(org.hibernate.cfg.AvailableSettings.USE_SECOND_LEVEL_CACHE, false);
        props.put(org.hibernate.cfg.AvailableSettings.USE_QUERY_CACHE, false);
        props.put(org.hibernate.cfg.AvailableSettings.USE_STRUCTURED_CACHE, false);
        props.put(org.hibernate.cfg.AvailableSettings.STATEMENT_BATCH_SIZE, 20);
        props.put(org.hibernate.cfg.AvailableSettings.PHYSICAL_NAMING_STRATEGY, CamelCaseToUnderscoresNamingStrategy.class.getName());

        return props;
    }

    @Override
    public DataSource getJtaDataSource() {
        return null;
    }

    @Override
    public DataSource getNonJtaDataSource() {
        return null;
    }

    @Override
    public List<String> getMappingFileNames() {
        return Collections.emptyList();
    }

    @Override
    public List<URL> getJarFileUrls() {
        return Collections.emptyList();
    }

    @Override
    public URL getPersistenceUnitRootUrl() {
        return null;
    }

    @Override
    public boolean excludeUnlistedClasses() {
        return true;
    }

    @Override
    public SharedCacheMode getSharedCacheMode() {
        return null;
    }

    @Override
    public ValidationMode getValidationMode() {
        return null;
    }

    @Override
    public String getPersistenceXMLSchemaVersion() {
        return null;
    }

    @Override
    public ClassLoader getClassLoader() {
        return null;
    }

    @Override
    public void addTransformer(ClassTransformer transformer) {}

    @Override
    public ClassLoader getNewTempClassLoader() {
        return null;
    }

    private DatabaseConfiguration() {
    }
}
