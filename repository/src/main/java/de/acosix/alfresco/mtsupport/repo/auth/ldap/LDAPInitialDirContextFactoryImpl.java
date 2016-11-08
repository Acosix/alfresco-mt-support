/*
 * Copyright 2016 Acosix GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.acosix.alfresco.mtsupport.repo.auth.ldap;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Map.Entry;

import javax.naming.AuthenticationNotSupportedException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.repo.security.authentication.AuthenticationDiagnostic;
import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.ldap.LDAPInitialDirContextFactory;
import org.alfresco.util.PropertyCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import de.acosix.alfresco.utility.common.security.ThreadSafeSSLSocketFactory;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class LDAPInitialDirContextFactoryImpl implements LDAPInitialDirContextFactory, InitializingBean
{

    private static final Logger LOGGER = LoggerFactory.getLogger(LDAPInitialDirContextFactoryImpl.class);

    public static final String PROTOCOL_SSL = "ssl";

    protected Map<String, String> defaultEnvironment = Collections.<String, String> emptyMap();

    protected Map<String, String> authenticatedEnvironment = Collections.<String, String> emptyMap();

    // not existent in 5.0.d
    protected Map<String, String> poolSystemProperties = Collections.<String, String> emptyMap();

    protected String trustStorePath;

    protected String trustStoreType;

    protected String trustStorePassPhrase;

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet() throws Exception
    {
        // handled as part of setter in default class
        if (this.poolSystemProperties != null)
        {
            for (final Entry<String, String> entry : this.poolSystemProperties.entrySet())
            {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }

        // check anonymous bind
        final Map<String, String> config = new HashMap<>(this.authenticatedEnvironment.size());
        config.putAll(this.authenticatedEnvironment);
        config.remove(Context.SECURITY_PRINCIPAL);
        config.remove(Context.SECURITY_CREDENTIALS);

        if (this.isSSLSocketFactoryRequired(config))
        {
            final KeyStore trustStore = this.initTrustStore();
            ThreadSafeSSLSocketFactory.initTrustedSSLSocketFactory(trustStore);
            config.put("java.naming.ldap.factory.socket", ThreadSafeSSLSocketFactory.class.getName());
        }

        try
        {
            new InitialDirContext(new Hashtable<>(config));
            LOGGER.warn("LDAP server supports anonymous bind {}", config.get(Context.PROVIDER_URL));
        }
        catch (javax.naming.AuthenticationException | AuthenticationNotSupportedException ax)
        {
            // NO-OP - expected
        }
        catch (final NamingException nx)
        {
            LOGGER.error("Unable to connect to LDAP Server; check LDAP configuration", nx);
            return;
        }

        // Simple DN and password
        config.put(Context.SECURITY_PRINCIPAL, "daftAsABrush");
        config.put(Context.SECURITY_CREDENTIALS, "daftAsABrush");

        try
        {
            new InitialDirContext(new Hashtable<>(config));
            throw new AuthenticationException("The ldap server at " + config.get(Context.PROVIDER_URL)
                    + " falls back to use anonymous bind if invalid security credentials are presented. This is not supported.");
        }
        catch (javax.naming.AuthenticationException | AuthenticationNotSupportedException ax)
        {
            LOGGER.info("LDAP server does not fall back to anonymous bind for a string uid and password at {}",
                    config.get(Context.PROVIDER_URL));
        }
        catch (final NamingException nx)
        {
            LOGGER.info("LDAP server does not support simple string user ids and invalid credentials at {}",
                    config.get(Context.PROVIDER_URL));
        }

        // DN and password
        config.put(Context.SECURITY_PRINCIPAL, "cn=daftAsABrush,dc=woof");
        config.put(Context.SECURITY_CREDENTIALS, "daftAsABrush");
        try
        {
            new InitialDirContext(new Hashtable<>(config));
            throw new AuthenticationException("The ldap server at " + config.get(Context.PROVIDER_URL)
                    + " falls back to use anonymous bind if invalid security credentials are presented. This is not supported.");
        }
        catch (javax.naming.AuthenticationException | AuthenticationNotSupportedException ax)
        {
            LOGGER.info("LDAP server does not fall back to anonymous bind for a simple dn and password at {}",
                    config.get(Context.PROVIDER_URL));
        }
        catch (final NamingException nx)
        {
            LOGGER.info("LDAP server does not support simple DN and invalid credentials at {}", config.get(Context.PROVIDER_URL));
        }

        // Check more if we have a real principal we expect to work
        final String principal = this.defaultEnvironment.get(Context.SECURITY_PRINCIPAL);
        if (principal != null)
        {
            config.put(Context.SECURITY_PRINCIPAL, principal);
            config.put(Context.SECURITY_CREDENTIALS, "sdasdasdasdasd123123123");

            try
            {
                new InitialDirContext(new Hashtable<>(config));
                throw new AuthenticationException("The ldap server at " + config.get(Context.PROVIDER_URL)
                        + " falls back to use anonymous bind for a known principal if invalid security credentials are presented. This is not supported.");
            }
            catch (final javax.naming.AuthenticationException ax)
            {
                LOGGER.info("LDAP server does not fall back to anonymous bind for known principal and invalid password at {}",
                        config.get(Context.PROVIDER_URL));
            }
            catch (final AuthenticationNotSupportedException ax)
            {
                LOGGER.info("LDAP server does not support the required authentication mechanism");
            }
            catch (final NamingException nx)
            {
                // NO-OP - covered in previous checks
            }
        }
    }

    /**
     * @param defaultEnvironment
     *            the defaultEnvironment to set
     */
    public void setDefaultEnvironment(final Map<String, String> defaultEnvironment)
    {
        this.defaultEnvironment = defaultEnvironment;
        if (this.defaultEnvironment != null)
        {
            this.defaultEnvironment.values().removeAll(Arrays.asList(null, ""));
        }
    }

    /**
     * @param authenticatedEnvironment
     *            the authenticatedEnvironment to set
     */
    public void setAuthenticatedEnvironment(final Map<String, String> authenticatedEnvironment)
    {
        this.authenticatedEnvironment = authenticatedEnvironment;
        if (this.authenticatedEnvironment != null)
        {
            this.authenticatedEnvironment.values().removeAll(Arrays.asList(null, ""));
        }
    }

    /**
     * @param poolSystemProperties
     *            the poolSystemProperties to set
     */
    public void setPoolSystemProperties(final Map<String, String> poolSystemProperties)
    {
        this.poolSystemProperties = poolSystemProperties;
        if (this.poolSystemProperties != null)
        {
            this.poolSystemProperties.values().removeAll(Arrays.asList(null, ""));
        }
    }

    /**
     * @param trustStorePath
     *            the trustStorePath to set
     */
    public void setTrustStorePath(final String trustStorePath)
    {
        if (PropertyCheck.isValidPropertyString(trustStorePath))
        {
            this.trustStorePath = trustStorePath;
        }
    }

    /**
     * @param trustStoreType
     *            the trustStoreType to set
     */
    public void setTrustStoreType(final String trustStoreType)
    {
        if (PropertyCheck.isValidPropertyString(trustStoreType))
        {
            this.trustStoreType = trustStoreType;
        }
    }

    /**
     * @param trustStorePassPhrase
     *            the trustStorePassPhrase to set
     */
    public void setTrustStorePassPhrase(final String trustStorePassPhrase)
    {
        if (PropertyCheck.isValidPropertyString(trustStorePassPhrase))
        {
            this.trustStorePassPhrase = trustStorePassPhrase;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    // does not make sense to be required by interface
    public void setInitialDirContextEnvironment(final Map<String, String> environment)
    {
        this.setAuthenticatedEnvironment(environment);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InitialDirContext getDefaultIntialDirContext(final int pageSize, final AuthenticationDiagnostic diagnostic)
            throws AuthenticationException
    {
        final Map<String, String> config = new HashMap<>(this.defaultEnvironment.size());
        config.putAll(this.defaultEnvironment);
        final InitialDirContext defaultInitialDirContext = this.buildInitialDirContext(config, pageSize, diagnostic);
        return defaultInitialDirContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InitialDirContext getDefaultIntialDirContext(final int pageSize) throws AuthenticationException
    {
        return this.getDefaultIntialDirContext(pageSize, new AuthenticationDiagnostic());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InitialDirContext getDefaultIntialDirContext() throws AuthenticationException
    {
        return this.getDefaultIntialDirContext(0, new AuthenticationDiagnostic());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InitialDirContext getDefaultIntialDirContext(final AuthenticationDiagnostic diagnostic) throws AuthenticationException
    {
        return this.getDefaultIntialDirContext(0, diagnostic);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    // copied from default Alfresco class
    public boolean hasNextPage(final DirContext ctx, final int pageSize)
    {
        if (pageSize > 0)
        {
            try
            {
                final LdapContext ldapContext = (LdapContext) ctx;
                final Control[] controls = ldapContext.getResponseControls();

                // Retrieve the paged result cookie if there is one
                if (controls != null)
                {
                    for (final Control control : controls)
                    {
                        if (control instanceof PagedResultsResponseControl)
                        {
                            final byte[] cookie = ((PagedResultsResponseControl) control).getCookie();
                            if (cookie != null)
                            {
                                // Prepare for next page
                                ldapContext
                                        .setRequestControls(new Control[] { new PagedResultsControl(pageSize, cookie, Control.CRITICAL) });
                                return true;
                            }
                        }
                    }
                }
            }
            catch (final NamingException nx)
            {
                throw new AuthenticationException("Unable to connect to LDAP Server; check LDAP configuration", nx);
            }
            catch (final IOException e)
            {
                throw new AuthenticationException("Unable to encode LDAP v3 request controls; check LDAP configuration", e);
            }

        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InitialDirContext getInitialDirContext(final String principal, final String credentials) throws AuthenticationException
    {
        return this.getInitialDirContext(principal, credentials, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InitialDirContext getInitialDirContext(final String principal, final String credentials,
            final AuthenticationDiagnostic diagnostic) throws AuthenticationException
    {
        final AuthenticationDiagnostic effectiveDiagnostic = diagnostic != null ? diagnostic : new AuthenticationDiagnostic();

        if (principal == null)
        {
            // failed before we tried to do anything
            effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_VALIDATION, false, null);
            throw new AuthenticationException("Null user name provided.", effectiveDiagnostic);
        }

        if (principal.length() == 0)
        {
            effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_VALIDATION, false, null);
            throw new AuthenticationException("Empty user name provided.", effectiveDiagnostic);
        }

        if (credentials == null)
        {
            effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_VALIDATION, false, null);
            throw new AuthenticationException("No credentials provided.", effectiveDiagnostic);
        }

        if (credentials.length() == 0)
        {
            effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_VALIDATION, false, null);
            throw new AuthenticationException("Empty credentials provided.", effectiveDiagnostic);
        }

        effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_VALIDATION, true, null);

        final Map<String, String> config = new HashMap<>(this.authenticatedEnvironment.size());
        config.putAll(this.authenticatedEnvironment);
        config.put(Context.SECURITY_PRINCIPAL, principal);
        config.put(Context.SECURITY_CREDENTIALS, credentials);

        final InitialDirContext initialDirContext = this.buildInitialDirContext(config, 0, effectiveDiagnostic);
        return initialDirContext;
    }

    // mostly copied from default Alfresco class
    protected InitialDirContext buildInitialDirContext(final Map<String, String> config, final int pageSize,
            final AuthenticationDiagnostic diagnostic) throws AuthenticationException
    {
        final AuthenticationDiagnostic effectiveDiagnostic = diagnostic != null ? diagnostic : new AuthenticationDiagnostic();

        final String securityPrincipal = config.get(Context.SECURITY_PRINCIPAL);
        final String providerURL = config.get(Context.PROVIDER_URL);

        if (this.isSSLSocketFactoryRequired(config))
        {
            final KeyStore trustStore = this.initTrustStore();
            ThreadSafeSSLSocketFactory.initTrustedSSLSocketFactory(trustStore);
            config.put("java.naming.ldap.factory.socket", ThreadSafeSSLSocketFactory.class.getName());
        }

        try
        {
            // If a page size has been requested, use LDAP v3 paging
            if (pageSize > 0)
            {
                final InitialLdapContext ctx = new InitialLdapContext(new Hashtable<>(config), null);
                ctx.setRequestControls(new Control[] { new PagedResultsControl(pageSize, Control.CRITICAL) });
                return ctx;
            }
            else
            {
                final InitialDirContext ret = new InitialDirContext(new Hashtable<>(config));
                final Object[] args = { providerURL, securityPrincipal };
                effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_LDAP_CONNECTED, true, args);
                return ret;
            }
        }
        catch (final javax.naming.AuthenticationException ax)
        {
            final Object[] args1 = { securityPrincipal };
            final Object[] args = { providerURL, securityPrincipal };
            effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_LDAP_CONNECTED, true, args);
            effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_LDAP_AUTHENTICATION, false, args1);

            // wrong user/password - if we get this far the connection is O.K
            final Object[] args2 = { securityPrincipal, ax.getLocalizedMessage() };
            throw new AuthenticationException("authentication.err.authentication", effectiveDiagnostic, args2, ax);
        }
        catch (final CommunicationException ce)
        {
            final Object[] args1 = { providerURL };
            effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_LDAP_CONNECTING, false, args1);

            final StringBuffer message = new StringBuffer();

            message.append(ce.getClass().getName() + ", " + ce.getMessage());

            Throwable cause = ce.getCause();
            while (cause != null)
            {
                message.append(", ");
                message.append(cause.getClass().getName() + ", " + cause.getMessage());
                cause = cause.getCause();
            }

            // failed to connect
            final Object[] args = { providerURL, message.toString() };
            throw new AuthenticationException("authentication.err.communication", effectiveDiagnostic, args, ce);
        }
        catch (final NamingException nx)
        {
            final Object[] args = { providerURL };
            effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_LDAP_CONNECTING, false, args);

            final StringBuffer message = new StringBuffer();

            message.append(nx.getClass().getName() + ", " + nx.getMessage());

            Throwable cause = nx.getCause();
            while (cause != null)
            {
                message.append(", ");
                message.append(cause.getClass().getName() + ", " + cause.getMessage());
                cause = cause.getCause();
            }

            // failed to connect
            final Object[] args1 = { providerURL, message.toString() };
            throw new AuthenticationException("authentication.err.connection", effectiveDiagnostic, args1, nx);
        }
        catch (final IOException e)
        {
            final Object[] args = { providerURL, securityPrincipal };
            effectiveDiagnostic.addStep(AuthenticationDiagnostic.STEP_KEY_LDAP_CONNECTED, true, args);

            throw new AuthenticationException("Unable to encode LDAP v3 request controls", e);
        }
    }

    // this is one of the changes: use the actual configuration to check if SSL is required
    protected boolean isSSLSocketFactoryRequired(final Map<String, String> config)
    {
        boolean result = false;
        // Check for LDAPS config
        final String protocol = config.get(Context.SECURITY_PROTOCOL);
        if (protocol != null && protocol.equals(PROTOCOL_SSL))
        {
            if (this.trustStoreType != null && this.trustStorePath != null && this.trustStorePassPhrase != null)
            {
                result = true;
            }
            else
            {
                LOGGER.warn("The SSL configuration for LDAPS is not full, the default configuration will be used.");
            }
        }
        return result;
    }

    protected KeyStore initTrustStore()
    {
        KeyStore ks;
        final String trustStoreType = this.trustStoreType;
        try
        {
            ks = KeyStore.getInstance(trustStoreType);
        }
        catch (final KeyStoreException kse)
        {
            throw new AlfrescoRuntimeException("No provider supports " + trustStoreType, kse);
        }
        try
        {
            final FileInputStream fis = new FileInputStream(this.trustStorePath);
            try
            {
                ks.load(fis, this.trustStorePassPhrase.toCharArray());
            }
            finally
            {
                try
                {
                    fis.close();
                }
                catch (final IOException ignore)
                {
                    // NO-OP
                }
            }
        }
        catch (final FileNotFoundException fnfe)
        {
            throw new AlfrescoRuntimeException("The truststore file is not found.", fnfe);
        }
        catch (final IOException ioe)
        {
            throw new AlfrescoRuntimeException("The truststore file cannot be read.", ioe);
        }
        catch (final NoSuchAlgorithmException nsae)
        {
            throw new AlfrescoRuntimeException("Algorithm used to check the integrity of the truststore cannot be found.", nsae);
        }
        catch (final CertificateException ce)
        {
            throw new AlfrescoRuntimeException("The certificates cannot be loaded from truststore.", ce);
        }
        return ks;
    }
}
