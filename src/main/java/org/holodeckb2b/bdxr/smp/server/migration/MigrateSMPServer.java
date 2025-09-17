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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.stream.Collectors;

import org.bouncycastle.crypto.CryptoException;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.holodeckb2b.bdxr.common.datamodel.impl.IdentifierImpl;
import org.holodeckb2b.bdxr.smp.server.ui.auth.UserRole;
import org.holodeckb2b.bdxr.smp.server.utils.DataEncryptor;
import org.holodeckb2b.commons.security.KeystoreUtils;
import org.holodeckb2b.commons.util.Utils;

/**
 * Is an application to migrate both the server configuration data as well as the managed meta-data from version 2.1.x 
 * to version 3.0.y of the Holodeck SMP server.  
 * 
 * @author Sander Fieten (sander at holodeck-b2b.org)
 */
public class MigrateSMPServer {
	
	public static void main(String[] args) {
		new MigrateSMPServer(args).migrate();
	}
	
	private static final String P_CREATE_NEW_DB = "create.new.db";
	private static final String P_MASTERPWD = "new.masterpwd";
	
	private Properties 	config;
	private Connection	oldDbConn;
	private Connection	newDbConn;
	
	private final static Timestamp	NOW = Timestamp.from(Instant.now());
	
	MigrateSMPServer(String[] args) {
		List<String> params = Arrays.asList(args);
		config = new Properties();

		if (params.indexOf("-masterpwd") < 0) {
			System.err.println("Missing parameter -masterpwd");
			System.exit(-1);
		}
		config.put(P_MASTERPWD, params.get(params.indexOf("-masterpwd") + 1));
			
		if (params.indexOf("-config") >= 0) {
			try (FileInputStream fis = new FileInputStream(params.get(params.indexOf("-config") + 1))) {
				config.load(fis);
			} catch (IOException e) {
				System.err.println("Could not load configuration file : " + e.getMessage());
				System.exit(-1);
			}
		}
		
		if (Utils.isNullOrEmpty(config.getProperty("old.db.url")))
			config.put("old.db.url", "jdbc:h2:./smpdata");
		if (Utils.isNullOrEmpty(config.getProperty("old.db.user")))
			config.put("old.db.user", "hb2b_smp");		
		if (Utils.isNullOrEmpty(config.getProperty("old.db.password")))
			config.put("old.db.password", "hb2b_smp");
		
		if (Utils.isNullOrEmpty(config.getProperty("new.db.url")))
			config.put("new.db.url", "jdbc:h2:./smpdataV3");
		if (Utils.isNullOrEmpty(config.getProperty("new.db.user")))
			config.put("new.db.user", "hb2b_smp");		
		if (Utils.isNullOrEmpty(config.getProperty("new.db.password")))
			config.put("new.db.password", "hb2b_smp");						
		if (!Utils.isNullOrEmpty(config.getProperty("new.db.schema")))
			config.put("new.db.extra.hibernate.default_schema", config.getProperty("new.db.schema"));				
		
		config.put(P_CREATE_NEW_DB, !params.contains("-skipCreate"));
	}
	
	private void migrate() {
		if ((boolean) config.get(P_CREATE_NEW_DB)) 
			createNewDatabase();
		
		try {
			oldDbConn = connectToOldDb();
			newDbConn = connectToNewDb();

			migrateServerConfig();
			migrateUsers();
			
			migrateIDSchemes();
			migrateTransportProfiles();
			migrateServices();
			migrateProcesses();
			migrateEndpoints();
			migrateSMT();
			migrateProcGroups();
			migrateParticipants();
			migrateBindings();			
		} catch (SQLException e) {
			System.err.println("Error during migration: " + Utils.getExceptionTrace(e));
		} finally {
			if (oldDbConn != null)
				try { oldDbConn.close(); } catch (SQLException e) { }
			if (newDbConn != null)
				try { newDbConn.close(); } catch (SQLException e) { }
		}
			
	}

	private void createNewDatabase() {
		try {
			System.out.println("Creating new database");
			new HibernatePersistenceProvider()
					.createContainerEntityManagerFactory(DatabaseConfiguration.VERSION_3_CFG, 
							config.entrySet().stream()
									.filter(e -> ((String) e.getKey()).startsWith("new.db."))
									.collect(Collectors.toMap(e ->											
												((String) e.getKey()).replace("new.db", "jakarta.persistence.jdbc")
																	 .replace("jakarta.persistence.jdbc.extra.", ""), 
												e -> e.getValue())));
			System.out.println("Created new database");
		} catch (Throwable t) {
			System.err.println("Could not create new database : " + Utils.getExceptionTrace(t));
			t.printStackTrace();
			System.exit(-1);
		}
	}
	
	private void migrateServerConfig() throws SQLException {
		System.out.println("Migrating server configuration");
		
		StringBuilder insertQuery = new StringBuilder("INSERT INTO ");
		if (config.getProperty("new.db.schema") != null)
			insertQuery.append(config.getProperty("new.db.schema")).append('.');
		insertQuery.append("SERVER_CONFIG_ENTITY (OID, LAST_MODIFIED, CURRENT_KEY_PAIR) VALUES(?, ?, ?);");
		try (PreparedStatement insert = newDbConn.prepareStatement(insertQuery.toString())) {
			insert.setLong(1, 1L);
			insert.setTimestamp(2, NOW);
			insert.setBytes(3, getServerKeyPair());
			insert.execute();
			newDbConn.commit();
		} 
		
		migrateData("SML Registration", "SELECT HOSTNAME, IDENTIFIER, IP_ADDRESS FROM SMLREGISTRATION;",
				"UPDATE SERVER_CONFIG_ENTITY\n"
				+ "SET REGISTEREDSML=?, SMP_ID=?, BASE_URL=?, IPV4ADDRESS=?\n"
				+ "WHERE OID = 1;",
				(rs, insert) -> {
					insert.setBoolean(1, true);
					insert.setString(2, rs.getString("IDENTIFIER"));
					insert.setString(3, "http://" + rs.getString("HOSTNAME"));
					insert.setString(4, rs.getString("IP_ADDRESS"));
				});
	}
	
	private byte[] getServerKeyPair() {
		System.out.println("Reading server certificate");
		String certFile = config.get("old.certfile") != null ? (String) config.get("old.certfile") : "signkeypair.p12";
		try (Scanner s = new Scanner(new File(certFile + ".pwd"));
			 FileInputStream fis = new FileInputStream(certFile);
			 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
			
			KeystoreUtils.saveKeyPairToPKCS12(KeystoreUtils.readKeyPairFromKeystore(fis, 
																				s.hasNextLine() ? s.nextLine() : null), 
											  baos, null);
			System.out.println("Read server certificate, encrypting it for use in new version");
			byte[] encrypted = new DataEncryptor(config.getProperty(P_MASTERPWD)).encrypt(baos.toByteArray());
			System.out.println("Encrypted server certificate");
			return encrypted;
		} catch (FileNotFoundException e) {
			System.err.println("Could not find certificate file : " + certFile);
			System.exit(-1);
		} catch (IOException | CertificateException | CryptoException e1) {
			System.err.println("Error processing certificate file (" + certFile + ") : " + e1.getMessage());
			System.exit(-1);
		}
		return null;
	}
	
	private void migrateUsers() throws SQLException {
		migrateData("Users", "SELECT * FROM USER_ACCOUNT",
				"INSERT INTO USER_ACCOUNT\n"
				+ "(OID, EMAIL_ADDRESS, FAILED2FA_ATTEMPTS, FAILED_ATTEMPTS, LOCKED, FULL_NAME, PASSWORD)\n"
				+ "VALUES(?, ?, 0, 0, ?, ?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("OID"));
					insert.setString(2, rs.getString("EMAIL_ADDRESS"));
					insert.setBoolean(3, false);
					insert.setString(4, rs.getString("FULL_NAME"));
					insert.setString(5, rs.getString("PASSWORD"));
				});
		migrateData("User Roles", "SELECT * FROM USER_ACCOUNT_ROLES",
				"INSERT INTO USER_ACCOUNT_ROLES(USER_ACCOUNT_OID, ROLES) VALUES(?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("USER_ACCOUNT_OID"));
					insert.setInt(2, UserRole.valueOf(rs.getString("ROLES")).ordinal());
				});
		updateSequence("USER_ACCOUNT");
	}
	
	private void migrateIDSchemes() throws SQLException {
		migrateData("ID Schemes", "SELECT * FROM IDSCHEME",
				 "INSERT INTO IDSCHEME "
				 + "(OID, LAST_MODIFIED, NAME, AGENCY, CASE_SENSITIVE, SCHEME_ID, SCHEME_SPECIFICATION_REF) "
				 + "VALUES(?, ?, ?, ?, ?, ?, ?);", 
				 (rs, insert) -> {
					insert.setLong(1, rs.getRow());
					insert.setTimestamp(2, NOW);
					insert.setString(3, rs.getString("NAME"));
					insert.setString(4, rs.getString("AGENCY"));
					insert.setBoolean(5, rs.getBoolean("CASE_SENSITIVE"));
					insert.setString(6, rs.getString("SCHEME_ID"));
					insert.setString(7, rs.getString("LOCATIONURI"));
				 });
		updateSequence("IDSCHEME");
	}
	
	private void migrateTransportProfiles() throws SQLException {
		migrateData("Transport Profiles", "SELECT * FROM TRANSPORT_PROFILE",
				"INSERT INTO TRANSPORT_PROFILE\n"
				+ "(OID, LAST_MODIFIED, NAME, IDVALUE, SPECIFICATION_REF)\n"
				+ "VALUES(?, ?, ?, ?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getRow());
					insert.setTimestamp(2, NOW);
					insert.setString(3, rs.getString("ID"));
					insert.setString(4, rs.getString("ID"));
					insert.setString(5, rs.getString("SPECIFICATION_REF"));
				});
		updateSequence("TRANSPORT_PROFILE");
	}
	
	private void migrateServices() throws SQLException {
		migrateData("Services", "SELECT * FROM SERVICE",
					 "INSERT INTO SERVICE\n"
					 + "(OID, LAST_MODIFIED, NAME, IDVALUE, SPECIFICATION_REF, IDSCHEME)\n"
					 + "VALUES(?, ?, ?, ?, ?, ?);",
					 (rs, insert) -> {
						insert.setLong(1, rs.getLong("OID"));
						insert.setTimestamp(2, NOW);
						insert.setString(3, rs.getString("NAME"));
						insert.setString(4, rs.getString("IDVALUE"));
						insert.setString(5, rs.getString("SPECIFICATION_REF"));
						if (!Utils.isNullOrEmpty(rs.getString("SCHEME_SCHEME_ID"))) {
							insert.setLong(6, getIDSchemeOID(rs.getString("SCHEME_SCHEME_ID")));
						} else
							insert.setNull(6, Types.BIGINT);
					 });
		updateSequence("SERVICE");
	}
	
	private void migrateProcesses() throws SQLException {
		migrateData("Processes", "SELECT * FROM PROCESS",
				"INSERT INTO PROCESS\n"
						+ "(OID, LAST_MODIFIED, NAME, IDVALUE, SPECIFICATION_REF, IDSCHEME)\n"
						+ "VALUES(?, ?, ?, ?, ?, ?);",
						(rs, insert) -> {
							insert.setLong(1, rs.getLong("OID"));
							insert.setTimestamp(2, NOW);
							insert.setString(3, rs.getString("NAME"));
							insert.setString(4, rs.getString("IDVALUE"));
							insert.setString(5, rs.getString("SPECIFICATION_REF"));
							if (!Utils.isNullOrEmpty(rs.getString("SCHEME_SCHEME_ID"))) {
								insert.setLong(6, getIDSchemeOID(rs.getString("SCHEME_SCHEME_ID")));
							} else
								insert.setNull(6, Types.BIGINT);
						});
		updateSequence("PROCESS");
	}
	
	private void migrateEndpoints() throws SQLException {
		migrateData("General Endpoint Info", "SELECT * FROM ENDPOINT",
				"INSERT INTO ENDPOINT\n"
				+ "(OID, LAST_MODIFIED, NAME, CONTACT_INFO, DESCRIPTION, SERVICE_ACTIVATION_DATE, SERVICE_EXPIRATION_DATE, URL, TRANSPORT_PROFILE_OID)\n"
				+ "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("OID"));
					insert.setTimestamp(2, NOW);
					insert.setString(3, rs.getString("NAME"));
					insert.setString(4, rs.getString("CONTACT_INFO"));
					insert.setString(5, rs.getString("DESCRIPTION"));
					insert.setTimestamp(6, rs.getTimestamp("SERVICE_ACTIVATION_DATE"));
					insert.setTimestamp(7, rs.getTimestamp("SERVICE_EXPIRATION_DATE"));
					insert.setString(8, rs.getString("URL"));
					StringBuilder selectTpQuery = new StringBuilder("SELECT OID FROM ");
					if (config.getProperty("new.db.schema") != null) 
						selectTpQuery.append(config.getProperty("new.db.schema")).append(".");
					selectTpQuery.append("TRANSPORT_PROFILE WHERE IDVALUE = '").append(rs.getString("PROFILE_ID")).append("'");
					ResultSet rs2 = newDbConn.createStatement().executeQuery(selectTpQuery.toString());
					if (rs2.next()) insert.setLong(9, rs2.getLong(1));
				});
		updateSequence("ENDPOINT");
		migrateData("Endpoint Certificates", 
				"SELECT ec.ENDPOINT_OID, c.*\n"
				+ "FROM CERTIFICATE c, ENDPOINT_CERTIFICATES ec\n"
				+ "WHERE \n"
				+ "	ec.CERTIFICATES_OID = c.OID",
				"INSERT INTO EP_CERTIFICATES\n"
				+ "(ENDPOINT_OID, ACTIVATION_DATE, CERT, DESCRIPTION, EXPIRATION_DATE, TXT_USAGE)\n"
				+ "VALUES(?, ?, ?, ?, ?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("ENDPOINT_OID"));
					insert.setTimestamp(2, rs.getTimestamp("ACTIVATION_DATE"));
					insert.setBytes(3, rs.getBytes("CERT"));
					insert.setString(4, rs.getString("DESCRIPTION"));
					insert.setTimestamp(5, rs.getTimestamp("EXPIRATION_DATE"));
					insert.setString(6, rs.getString("USAGE"));
				});
	}
	
	private void migrateSMT() throws SQLException {
		migrateData("Service Metadata Templates", "SELECT * FROM SERVICE_METADATA_TEMPLATE",
				"INSERT INTO SERVICE_METADATA_TEMPLATE\n"
				+ "(OID, LAST_MODIFIED, NAME, SERVICE_OID)\n"
				+ "VALUES(?, ?, ?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("OID"));
					insert.setTimestamp(2, NOW);
					insert.setString(3, rs.getString("NAME"));
					insert.setLong(4, rs.getLong("SERVICE_OID"));
				});
		updateSequence("SERVICE_METADATA_TEMPLATE");
	}
	
	private void migrateProcGroups() throws SQLException {
		migrateData("Process Group links to SMT", 
				"SELECT * FROM PROCESS_GROUP",
				"INSERT INTO PROCESS_GROUP (OID, TEMPLATE_OID) VALUES(?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("OID"));
					insert.setLong(2, rs.getLong("TEMPLATE_OID"));
				});
		updateSequence("PROCESS_GROUP");
		migrateData("Process Group Redirections", 
				"SELECT pg.OID, r.CERT, r.URL\n"
				+ "FROM PROCESS_GROUP pg, REDIRECTION r\n"
				+ "WHERE pg.REDIRECTION_OID = r.OID",
				"UPDATE PROCESS_GROUP SET CERT=?, URL=? WHERE OID=?;",
				(rs, update) -> {
					update.setLong(3, rs.getLong("OID"));
					update.setByte(1, rs.getByte("CERT"));
					update.setString(2, rs.getString("URL"));
				});
		migrateData("Process Group Endpoints", 
				"SELECT * FROM PROCESS_GROUP_ENDPOINTS;",
				"INSERT INTO PROCESS_GROUP_ENDPOINTS (PROCESS_GROUP_OID, ENDPOINTS_OID) VALUES(?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("PROCESS_GROUP_OID"));
					insert.setLong(2, rs.getLong("ENDPOINTS_OID"));
				});
		migrateData("Process Info", 
				"SELECT * FROM PROCESS_INFO;",
				"INSERT INTO PROCESS_INFO (OID, PROCESS_OID, PROCGROUP_OID) VALUES(?, ?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("OID"));
					insert.setLong(2, rs.getLong("PROCESS_OID"));
					insert.setLong(3, rs.getLong("PROCGROUP_OID"));
				});
		updateSequence("PROCESS_INFO");
		migrateData("Process Roles", 
				"SELECT PROCESS_INFO_OID, SCHEME_SCHEME_ID, IDVALUE FROM PI_ROLES;",
				"INSERT INTO PI_ROLES (PROCESS_INFO_OID, IDSCHEME, IDVALUE) VALUES(?, ?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("PROCESS_INFO_OID"));
					insert.setLong(3, rs.getLong("IDVALUE"));
					if (!Utils.isNullOrEmpty(rs.getString("SCHEME_SCHEME_ID"))) {
						insert.setLong(2, getIDSchemeOID(rs.getString("SCHEME_SCHEME_ID")));
					} else
						insert.setNull(2, Types.BIGINT);
				});
	}
	
	private void migrateParticipants() throws SQLException {
		migrateData("Participants", 
				"SELECT OID, ADDITIONAL_IDS, ADDRESS_INFO, CONTACT_INFO, COUNTRY, FIRST_REGISTRATION, IDVALUE, "
				+ "IS_REGISTEREDSML, MIGRATION_CODE, NAME, PUBLISHED_IN_DIRECTORY, SCHEME_SCHEME_ID\n"
				+ "FROM PARTICIPANT;",
				"INSERT INTO PARTICIPANT\n"
				+ "(OID, LAST_MODIFIED, NAME, IDVALUE, SMLMIGRATION_CODE, ADDITIONAL_IDS, FIRST_REGISTRATION, LCNAME, "
				+ "LOCATION_INFO, PUBLISHED_IN_DIRECTORY, REGISTERED_INSML, COUNTRY, IDSCHEME)\n"
				+ "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("OID"));
					insert.setTimestamp(2, NOW);
					insert.setString(3, rs.getString("NAME"));
					insert.setString(4, rs.getString("IDVALUE"));
					insert.setString(5, rs.getString("MIGRATION_CODE"));
					String oldAddIds = rs.getString("ADDITIONAL_IDS");
					if (!Utils.isNullOrEmpty(oldAddIds)) {
						StringBuilder newAddIds = new StringBuilder();
						String[] ids = oldAddIds.split(",");
						for (String id : ids) {
							IdentifierImpl idobj = IdentifierImpl.from(id);
							if (idobj.getScheme() != null) 
								newAddIds.append(idobj.getScheme().getSchemeId()).append('[')
										 .append(!idobj.getValue().equals(idobj.getValue().toLowerCase()))
										 .append("]::"); 
							newAddIds.append(idobj.getValue()).append(",");							
						}
						newAddIds.deleteCharAt(newAddIds.length() - 1);
						insert.setString(6, newAddIds.toString());
					} else
						insert.setString(6, null);
					insert.setTimestamp(7, rs.getTimestamp("FIRST_REGISTRATION"));
					insert.setString(8, !Utils.isNullOrEmpty(rs.getString("NAME")) ? rs.getString("NAME").toLowerCase() : null);
					insert.setString(9, rs.getString("CONTACT_INFO"));
					insert.setBoolean(10, rs.getBoolean("PUBLISHED_IN_DIRECTORY"));
					insert.setBoolean(11, rs.getBoolean("IS_REGISTEREDSML"));
					insert.setString(12, rs.getString("COUNTRY"));
					if (!Utils.isNullOrEmpty(rs.getString("SCHEME_SCHEME_ID"))) {
						insert.setLong(13, getIDSchemeOID(rs.getString("SCHEME_SCHEME_ID")));
					} else
						insert.setNull(13, Types.BIGINT);
				});
		updateSequence("PARTICIPANT");
	}
	
	private void migrateBindings() throws SQLException {
		migrateData("SMT Bindings",
				"SELECT PARTICIPANT_OID, TEMPLATE_OID FROM SERVICE_METADATA_BINDING;",
				"INSERT INTO SERVICE_METADATA_BINDING (PARTICIPANT_OID, TEMPLATE_OID) VALUES(?, ?);",
				(rs, insert) -> {
					insert.setLong(1, rs.getLong("PARTICIPANT_OID"));
					insert.setLong(2, rs.getLong("TEMPLATE_OID"));
				});
	}
	
	private PreparedStatement findIDScheme;
	
	private long getIDSchemeOID(String schemeId) throws SQLException {
		if (findIDScheme == null) {
			StringBuilder query =  new StringBuilder("SELECT OID FROM ");
			if (config.getProperty("new.db.schema") != null)
				query.append(config.getProperty("new.db.schema")).append('.');
			query.append("IDSCHEME WHERE SCHEME_ID = ?");
			findIDScheme = newDbConn.prepareStatement(query.toString());
		}
		findIDScheme.setString(1, schemeId); 
		try (ResultSet rs = findIDScheme.executeQuery()) {
			if (rs.next())
				return rs.getLong(1);
			else
				throw new SQLException("Could not find ID scheme: " + schemeId);
		}			
	}
	
	interface MigrateObject {
		void migrate(ResultSet rs, PreparedStatement insert) throws SQLException;
	}
	
	
	private void migrateData(String name, String selectQuery, String insertQuery, MigrateObject migrationFunction) throws SQLException {
		System.out.println("Migrating " + name);
		if (config.getProperty("old.db.schema") != null) {
			int from = selectQuery.indexOf("FROM");
			int where = selectQuery.indexOf("WHERE");
			StringBuilder select = new StringBuilder(selectQuery.substring(0, from + 4));
			String[] tables = selectQuery.substring(from + 5, where != -1 ? where : selectQuery.length()).split(",");
			for(int i = 0; i < tables.length; i++) {
				select.append(' ').append(config.getProperty("old.db.schema")).append('.').append(tables[i].trim());
				if (i < tables.length - 1) 
					select.append(",");			
			}
			if (where != -1)
				select.append(' ').append(selectQuery.substring(where));
			selectQuery = select.toString();
		}
		if (config.getProperty("new.db.schema") != null)
			if (insertQuery.startsWith("INSERT INTO "))
				insertQuery = insertQuery.replace("INTO ", "INTO " + config.getProperty("new.db.schema") + ".");
			else if (insertQuery.startsWith("UPDATE "))
				insertQuery = insertQuery.replace("UPDATE ", "UPDATE " + config.getProperty("new.db.schema") + ".");
		
		try (Statement retrieve = oldDbConn.createStatement();					
			PreparedStatement insert = newDbConn.prepareStatement(insertQuery);
			ResultSet rs = retrieve.executeQuery(selectQuery)) {
			long rows = 0;
			while (rs.next()) {
				migrationFunction.migrate(rs, insert);
				insert.executeUpdate();
				rows++;
			}
			newDbConn.commit();
			System.out.println("Migrated " + rows + " " + name);
		} catch (SQLException e) {
			newDbConn.rollback();
			throw e;
		} 
	}	
	
	private void updateSequence(String tableName) throws SQLException {
		System.out.println("Updating sequence for " + tableName);
		String withSchema = tableName;
		if (config.getProperty("new.db.schema") != null)
			withSchema = config.getProperty("new.db.schema") + "." + tableName;
		
		ResultSet rs = newDbConn.createStatement().executeQuery("SELECT MAX(OID) FROM " + withSchema);
		long nextVal = rs.next() ? rs.getLong(1) + 1 : 1;
		newDbConn.createStatement().executeUpdate("ALTER SEQUENCE " + withSchema + "_SEQ RESTART WITH " + nextVal);
		newDbConn.commit();
		System.out.println("Updated sequence for " + tableName + " at " + nextVal);
	}

	
	private Connection connectToOldDb() throws SQLException {
		try {
			Connection c = DriverManager.getConnection(config.getProperty("old.db.url"), 
											   config.getProperty("old.db.user"), 
											   config.getProperty("old.db.password"));
			c.setAutoCommit(false);
			return c;
		} catch (SQLException connError) {
			System.err.println("Could not connect to version 2 database : " + Utils.getExceptionTrace(connError));
			throw connError;
		}
	} 

	private Connection connectToNewDb() throws SQLException {
		try {
			Connection c = DriverManager.getConnection(config.getProperty("new.db.url"), 
											   config.getProperty("new.db.user"), 
											   config.getProperty("new.db.password"));
			c.setAutoCommit(false);
			return c;
		} catch (SQLException connError) {
			System.err.println("Could not connect to version 3 database : " + Utils.getExceptionTrace(connError));
			throw connError;
		}
	} 
}
