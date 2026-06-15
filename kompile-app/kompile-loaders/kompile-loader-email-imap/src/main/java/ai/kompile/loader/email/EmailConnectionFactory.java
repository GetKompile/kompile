/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.email;

import jakarta.mail.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Properties;

/**
 * Factory for creating connections to IMAP and POP3 mail servers.
 * Supports multiple authentication modes including OAuth2 via XOAUTH2 SASL mechanism.
 */
@Component
public class EmailConnectionFactory {

    private static final Logger logger = LoggerFactory.getLogger(EmailConnectionFactory.class);

    /**
     * Connects to an email server using the provided configuration.
     *
     * @param config The connection configuration
     * @return A connected Store instance
     * @throws MessagingException if connection fails
     */
    public Store connect(EmailConnectionConfig config) throws MessagingException {
        Properties props = buildProperties(config);
        Session session = Session.getInstance(props);

        if (logger.isDebugEnabled()) {
            session.setDebug(true);
        }

        String protocol = getProtocolName(config);
        Store store = session.getStore(protocol);

        try {
            if (config.isOAuth2()) {
                connectWithOAuth2(store, config);
            } else {
                connectWithPassword(store, config);
            }

            logger.info("Successfully connected to {} server: {}:{}",
                    config.getProtocol(), config.getHost(), config.getEffectivePort());

            return store;
        } catch (MessagingException e) {
            logger.error("Failed to connect to {} server: {}:{} - {}",
                    config.getProtocol(), config.getHost(), config.getEffectivePort(), e.getMessage());
            throw e;
        }
    }

    /**
     * Builds the JavaMail properties for the given configuration.
     */
    private Properties buildProperties(EmailConnectionConfig config) {
        Properties props = new Properties();
        String protocol = getProtocolName(config);
        String prefix = "mail." + protocol + ".";

        // Host and port
        props.put(prefix + "host", config.getHost());
        props.put(prefix + "port", String.valueOf(config.getEffectivePort()));

        // Timeouts
        props.put(prefix + "connectiontimeout", String.valueOf(config.getConnectionTimeout()));
        props.put(prefix + "timeout", String.valueOf(config.getReadTimeout()));

        // SSL/TLS configuration
        switch (config.getSecurity()) {
            case SSL:
            case TLS:
                props.put(prefix + "ssl.enable", "true");
                props.put(prefix + "ssl.protocols", "TLSv1.2 TLSv1.3");
                props.put(prefix + "ssl.trust", "*");
                break;
            case STARTTLS:
                props.put(prefix + "starttls.enable", "true");
                props.put(prefix + "starttls.required", "true");
                props.put(prefix + "ssl.trust", "*");
                break;
            case NONE:
                // No encryption
                break;
        }

        // OAuth2 SASL configuration
        if (config.isOAuth2()) {
            props.put(prefix + "auth.mechanisms", "XOAUTH2");
            props.put(prefix + "sasl.enable", "true");
            props.put(prefix + "sasl.mechanisms", "XOAUTH2");
            props.put("mail.imaps.auth.mechanisms", "XOAUTH2");
            props.put("mail.imaps.sasl.enable", "true");
            props.put("mail.imaps.sasl.mechanisms", "XOAUTH2");
        }

        // Additional IMAP-specific settings
        if (config.getProtocol() == EmailConnectionConfig.Protocol.IMAP) {
            props.put(prefix + "fetchsize", "1048576"); // 1MB fetch size
            props.put(prefix + "partialfetch", "false");
        }

        return props;
    }

    /**
     * Gets the JavaMail protocol name based on configuration.
     */
    private String getProtocolName(EmailConnectionConfig config) {
        boolean useSSL = config.getSecurity() == EmailConnectionConfig.Security.SSL
                || config.getSecurity() == EmailConnectionConfig.Security.TLS;

        if (config.getProtocol() == EmailConnectionConfig.Protocol.IMAP) {
            return useSSL ? "imaps" : "imap";
        } else {
            return useSSL ? "pop3s" : "pop3";
        }
    }

    /**
     * Connects using username and password authentication.
     */
    private void connectWithPassword(Store store, EmailConnectionConfig config) throws MessagingException {
        logger.debug("Connecting with password authentication to {}:{}",
                config.getHost(), config.getEffectivePort());

        store.connect(
                config.getHost(),
                config.getEffectivePort(),
                config.getUsername(),
                config.getPassword()
        );
    }

    /**
     * Connects using OAuth2 XOAUTH2 SASL mechanism.
     * This is used for Gmail and Microsoft 365.
     */
    private void connectWithOAuth2(Store store, EmailConnectionConfig config) throws MessagingException {
        logger.debug("Connecting with OAuth2 authentication to {}:{}",
                config.getHost(), config.getEffectivePort());

        if (config.getAccessToken() == null || config.getAccessToken().isEmpty()) {
            throw new MessagingException("OAuth2 access token is required but not provided");
        }

        // Build XOAUTH2 token string
        // Format: "user=" + email + "\001auth=Bearer " + accessToken + "\001\001"
        String xoauth2Token = buildXOAuth2Token(config.getUsername(), config.getAccessToken());

        // Connect using the XOAUTH2 token as the password
        store.connect(
                config.getHost(),
                config.getEffectivePort(),
                config.getUsername(),
                xoauth2Token
        );
    }

    /**
     * Builds the XOAUTH2 authentication string.
     *
     * Format: base64("user=" + email + "\001auth=Bearer " + accessToken + "\001\001")
     *
     * @param email The user's email address
     * @param accessToken The OAuth2 access token
     * @return The XOAUTH2 authentication string
     */
    public String buildXOAuth2Token(String email, String accessToken) {
        // Build the SASL XOAUTH2 initial client response
        // Format: "user=" + email + "\001auth=Bearer " + accessToken + "\001\001"
        String authString = String.format("user=%s\001auth=Bearer %s\001\001", email, accessToken);
        return Base64.getEncoder().encodeToString(authString.getBytes());
    }

    /**
     * Tests the connection without keeping it open.
     *
     * @param config The connection configuration
     * @return true if connection succeeds
     */
    public boolean testConnection(EmailConnectionConfig config) {
        try (Store store = connect(config)) {
            return store.isConnected();
        } catch (Exception e) {
            logger.warn("Connection test failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Lists available folders on the mail server (IMAP only).
     *
     * @param config The connection configuration
     * @return Array of folder names
     * @throws MessagingException if listing fails
     */
    public String[] listFolders(EmailConnectionConfig config) throws MessagingException {
        if (config.getProtocol() != EmailConnectionConfig.Protocol.IMAP) {
            return new String[]{"INBOX"};
        }

        try (Store store = connect(config)) {
            Folder defaultFolder = store.getDefaultFolder();
            Folder[] folders = defaultFolder.list("*");

            String[] folderNames = new String[folders.length];
            for (int i = 0; i < folders.length; i++) {
                folderNames[i] = folders[i].getFullName();
            }

            return folderNames;
        }
    }
}
