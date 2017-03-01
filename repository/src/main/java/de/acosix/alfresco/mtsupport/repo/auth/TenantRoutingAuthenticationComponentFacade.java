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
import java.util.concurrent.atomic.AtomicBoolean;

import org.alfresco.repo.management.subsystems.ActivateableBean;
import org.alfresco.repo.security.authentication.AbstractAuthenticationComponent;
import org.alfresco.repo.security.authentication.AuthenticationComponent;
import org.alfresco.repo.security.authentication.AuthenticationException;
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

import de.acosix.alfresco.mtsupport.repo.beans.TenantBeanUtils;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TenantRoutingAuthenticationComponentFacade extends AbstractAuthenticationComponent
        implements InitializingBean, ApplicationContextAware, ActivateableBean, BeanNameAware
{

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantRoutingAuthenticationComponentFacade.class);

    protected ApplicationContext applicationContext;

    protected String beanName;

    protected TenantService tenantService;

    protected TenantAdminService tenantAdminService;

    protected List<String> enabledTenants;

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet()
    {
        PropertyCheck.mandatory(this, "tenantService", this.tenantService);
        PropertyCheck.mandatory(this, "tenantAdminService", this.tenantAdminService);
        PropertyCheck.mandatory(this, "enabledTenants", this.enabledTenants);
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

    protected boolean isActive(final String tenantDomain)
    {
        boolean isActive = false;

        LOGGER.trace("Checking isActive for tenant {}", tenantDomain);
        if (this.enabledTenants.contains(tenantDomain) && (TenantUtil.DEFAULT_TENANT.equals(tenantDomain)
                || (this.tenantAdminService.existsTenant(tenantDomain) && this.tenantAdminService.isEnabledTenant(tenantDomain))))
        {
            final AuthenticationComponent authenticationComponent = TenantBeanUtils.getBeanForTenant(this.applicationContext, this.beanName,
                    tenantDomain, AuthenticationComponent.class);
            if (authenticationComponent instanceof ActivateableBean)
            {
                isActive = ((ActivateableBean) authenticationComponent).isActive();
            }

            LOGGER.trace("Tenant {} configured as active: {}", tenantDomain, isActive);
        }
        else
        {
            LOGGER.trace("Tenant {} does not exist or has not been enabled", tenantDomain);
        }

        return isActive;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    protected boolean implementationAllowsGuestLogin()
    {
        final AtomicBoolean guestLoginAllowed = new AtomicBoolean(false);

        LOGGER.debug("Checking guestUserAuthenticationAllowed for enabled tenants (until first supporting tenant)");
        for (final String tenantDomain : this.enabledTenants)
        {
            if (!guestLoginAllowed.get())
            {
                if (TenantUtil.DEFAULT_TENANT.equals(tenantDomain)
                        || (this.tenantAdminService.existsTenant(tenantDomain) && this.tenantAdminService.isEnabledTenant(tenantDomain)))
                {
                    final AuthenticationComponent authenticationComponent = TenantBeanUtils.getBeanForTenant(this.applicationContext,
                            this.beanName, tenantDomain, AuthenticationComponent.class);
                    final boolean guestUserAuthenticationAllowed = authenticationComponent.guestUserAuthenticationAllowed();
                    LOGGER.trace("Tenant {} allows guest user authentication: {}", tenantDomain, guestUserAuthenticationAllowed);
                    guestLoginAllowed.set(guestUserAuthenticationAllowed);
                }
            }
        }
        LOGGER.debug("Component allowed guest authentication: {}", guestLoginAllowed.get());

        return guestLoginAllowed.get();
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    protected void authenticateImpl(final String userName, final char[] password)
    {
        ParameterCheck.mandatoryString("userName", userName);

        AuthenticationComponent relevantAuthenticationComponent;
        final String primaryDomain = this.tenantService.getPrimaryDomain(userName);

        LOGGER.debug("Extracted primary domain {} from user {}",
                primaryDomain == null || TenantService.DEFAULT_DOMAIN.equals(primaryDomain) ? TenantUtil.DEFAULT_TENANT : primaryDomain,
                userName);

        if (primaryDomain == null || TenantService.DEFAULT_DOMAIN.equals(primaryDomain))
        {
            if (!this.isActive(TenantUtil.DEFAULT_TENANT))
            {
                LOGGER.debug("Failing authentication for user {} as tenant {} has not been enabled for this authentication subsystem",
                        userName, TenantUtil.DEFAULT_TENANT);
                throw new AuthenticationException(TenantUtil.DEFAULT_TENANT + " tenant does not support authentication");
            }

            relevantAuthenticationComponent = TenantBeanUtils.getBeanForTenant(this.applicationContext, this.beanName,
                    TenantUtil.DEFAULT_TENANT, AuthenticationComponent.class);
        }
        else if (!this.isActive(primaryDomain))
        {
            LOGGER.debug("Failing authentication for user {} as tenant {} has not been enabled for this authentication subsystem", userName,
                    primaryDomain);
            throw new AuthenticationException(primaryDomain + " tenant does not support authentication");
        }
        else
        {
            relevantAuthenticationComponent = TenantBeanUtils.getBeanForTenant(this.applicationContext, this.beanName, primaryDomain,
                    AuthenticationComponent.class);
        }

        relevantAuthenticationComponent.authenticate(userName, password);

        LOGGER.debug("Authenticated user {} with tenant {}", userName,
                primaryDomain == null || TenantService.DEFAULT_DOMAIN.equals(primaryDomain) ? TenantUtil.DEFAULT_TENANT : primaryDomain);
    }
}
