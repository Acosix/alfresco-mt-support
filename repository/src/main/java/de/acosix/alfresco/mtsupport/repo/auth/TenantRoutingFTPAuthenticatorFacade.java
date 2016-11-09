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
package de.acosix.alfresco.mtsupport.repo.auth;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.alfresco.filesys.alfresco.AlfrescoClientInfo;
import org.alfresco.filesys.auth.ftp.AlfrescoFtpAuthenticator;
import org.alfresco.jlan.ftp.FTPSrvSession;
import org.alfresco.jlan.server.auth.ClientInfo;
import org.alfresco.repo.tenant.TenantAdminService;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.repo.tenant.TenantUtil;
import org.alfresco.util.ParameterCheck;
import org.alfresco.util.PropertyCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TenantRoutingFTPAuthenticatorFacade extends AlfrescoFtpAuthenticator
        implements InitializingBean, ApplicationContextAware, BeanNameAware
{

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantRoutingFTPAuthenticatorFacade.class);

    protected ApplicationContext applicationContext;

    protected String beanName;

    protected TenantService tenantService;

    protected TenantAdminService tenantAdminService;

    protected List<String> enabledTenants;

    protected Map<String, Boolean> activeByTenant;

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet()
    {
        PropertyCheck.mandatory(this, "tenantService", this.tenantService);
        PropertyCheck.mandatory(this, "tenantAdminService", this.tenantAdminService);
        PropertyCheck.mandatory(this, "enabledTenants", this.enabledTenants);
        PropertyCheck.mandatory(this, "activeByTenant", this.activeByTenant);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setApplicationContext(final ApplicationContext applicationContext)
    {
        this.applicationContext = applicationContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBeanName(final String beanName)
    {
        this.beanName = beanName;
    }

    /**
     * @param tenantService
     *            the tenantService to set
     */
    public void setTenantService(final TenantService tenantService)
    {
        this.tenantService = tenantService;
    }

    /**
     * @param tenantAdminService
     *            the tenantAdminService to set
     */
    public void setTenantAdminService(final TenantAdminService tenantAdminService)
    {
        this.tenantAdminService = tenantAdminService;
    }

    public void setEnabledTenants(final String enabledTenants)
    {
        ParameterCheck.mandatoryString("enabledTenants", enabledTenants);
        this.enabledTenants = Arrays.asList(enabledTenants.split(","));
    }

    /**
     * @param activeByTenant
     *            the activeByTenant to set
     */
    public void setActiveByTenant(final Map<String, Boolean> activeByTenant)
    {
        this.activeByTenant = activeByTenant;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isActive()
    {
        final AtomicBoolean isActive = new AtomicBoolean(false);

        LOGGER.trace("Checking isActive for enabled tenants (until first active tenant)");
        for (final String tenantDomain : this.enabledTenants)
        {
            if (!isActive.get())
            {
                isActive.set(this.isActive(tenantDomain));
            }
        }
        LOGGER.trace("Component is active: {}", isActive.get());

        return isActive.get();
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public boolean authenticateUser(final ClientInfo client, final FTPSrvSession sess)
    {
        boolean result;

        // we need this override to re-check if FTP is enabled based on the tenant domain
        // previous isActive did not know about user

        if (client instanceof AlfrescoClientInfo)
        {
            // only default tenant can support anonymous guest login
            if (!client.isGuest())
            {
                final String userName = client.getUserName();

                final String primaryDomain = this.tenantService.getPrimaryDomain(userName);
                if (primaryDomain == null || TenantService.DEFAULT_DOMAIN.equals(primaryDomain))
                {
                    if (!this.isActive(TenantUtil.DEFAULT_TENANT))
                    {
                        LOGGER.debug(
                                "Failing authentication for {} from {} as tenant {} has not been enabled for this authentication subsystem",
                                client.getUserName(), client.getClientAddress(), TenantUtil.DEFAULT_TENANT);
                        result = false;
                    }
                    else
                    {
                        result = super.authenticateUser(client, sess);
                        LOGGER.debug("Authenticated {} from {} with tenant {}: {}", userName, client.getClientAddress(),
                                TenantUtil.DEFAULT_TENANT, result);
                    }
                }
                else if (!this.isActive(primaryDomain))
                {
                    LOGGER.debug(
                            "Failing authentication for {} from {} as tenant {} has not been enabled for this authentication subsystem",
                            client.getUserName(), client.getClientAddress(), primaryDomain);
                    result = false;
                }
                else
                {
                    result = super.authenticateUser(client, sess);
                    LOGGER.debug("Authenticated {} from {} with tenant {}: {}", userName, client.getClientAddress(), primaryDomain, result);

                }
            }
            else if (this.isActive(TenantUtil.DEFAULT_TENANT))
            {
                result = super.authenticateUser(client, sess);
                LOGGER.debug("Authenticated guest from {} with tenant {}: {}", client.getClientAddress(), TenantUtil.DEFAULT_TENANT,
                        result);
            }
            else
            {
                LOGGER.debug("Failing authentication for guest from {} as tenant {} has not been enabled for this authentication subsystem",
                        client.getClientAddress(), TenantUtil.DEFAULT_TENANT);
                result = false;
            }
        }
        else
        {
            if (client != null)
            {
                LOGGER.warn("Failing authentication as client is of unsupported type {}", client.getClass());
            }
            else
            {
                LOGGER.warn("Failing authentication as client is null");
            }
            result = false;
        }

        return result;
    }

    protected boolean isActive(final String tenantDomain)
    {
        boolean isActive = false;

        LOGGER.trace("Checking isActive for tenant {}", tenantDomain);
        if (this.enabledTenants.contains(tenantDomain) && (TenantUtil.DEFAULT_TENANT.equals(tenantDomain)
                || (this.tenantAdminService.existsTenant(tenantDomain) && this.tenantAdminService.isEnabledTenant(tenantDomain))))
        {
            final Boolean active = this.activeByTenant.get(tenantDomain);
            isActive = Boolean.TRUE.equals(active);

            LOGGER.trace("Tenant {} configured as active: {}", tenantDomain, isActive);
        }
        else
        {
            LOGGER.trace("Tenant {} does not exist or has not been enabled", tenantDomain);
        }

        return isActive;
    }

}
