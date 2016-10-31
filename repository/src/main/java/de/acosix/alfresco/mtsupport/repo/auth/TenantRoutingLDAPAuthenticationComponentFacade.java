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
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import de.acosix.alfresco.mtsupport.repo.beans.TenantBeanUtils;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TenantRoutingLDAPAuthenticationComponentFacade extends AbstractAuthenticationComponent
        implements InitializingBean, ApplicationContextAware, ActivateableBean, BeanNameAware
{

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

        this.enabledTenants.forEach(tenantDomain -> {
            if (!isActive.get())
            {
                isActive.set(this.isActive(tenantDomain));
            }
        });

        return isActive.get();
    }

    protected boolean isActive(final String tenantDomain)
    {
        boolean isActive = false;
        if (TenantUtil.DEFAULT_TENANT.equals(tenantDomain)
                || (this.tenantAdminService.existsTenant(tenantDomain) && this.tenantAdminService.isEnabledTenant(tenantDomain)))
        {
            final AuthenticationComponent authenticationComponent = TenantBeanUtils.getBeanForTenant(this.applicationContext, this.beanName,
                    tenantDomain, AuthenticationComponent.class);
            if (authenticationComponent instanceof ActivateableBean)
            {
                isActive = ((ActivateableBean) authenticationComponent).isActive();
            }
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

        this.enabledTenants.forEach(tenantDomain -> {
            if (!guestLoginAllowed.get())
            {
                if (TenantUtil.DEFAULT_TENANT.equals(tenantDomain)
                        || (this.tenantAdminService.existsTenant(tenantDomain) && this.tenantAdminService.isEnabledTenant(tenantDomain)))
                {
                    final AuthenticationComponent authenticationComponent = TenantBeanUtils.getBeanForTenant(this.applicationContext,
                            this.beanName, tenantDomain, AuthenticationComponent.class);
                    guestLoginAllowed.set(authenticationComponent.guestUserAuthenticationAllowed());
                }
            }
        });

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
        String baseUserName = userName;

        final String primaryDomain = this.tenantService.getPrimaryDomain(userName);
        if (primaryDomain == null || TenantService.DEFAULT_DOMAIN.equals(primaryDomain))
        {
            if (!this.isActive(TenantUtil.DEFAULT_TENANT))
            {
                throw new AuthenticationException(TenantUtil.DEFAULT_TENANT + " tenant does not support LDAP authentication");
            }

            relevantAuthenticationComponent = TenantBeanUtils.getBeanForTenant(this.applicationContext, this.beanName,
                    TenantUtil.DEFAULT_TENANT, AuthenticationComponent.class);
        }
        else if (!this.isActive(primaryDomain))
        {
            throw new AuthenticationException(primaryDomain + " tenant does not support LDAP authentication");
        }
        else
        {
            baseUserName = this.tenantService.getBaseNameUser(userName);
            relevantAuthenticationComponent = TenantBeanUtils.getBeanForTenant(this.applicationContext, this.beanName, userName,
                    AuthenticationComponent.class);
        }

        relevantAuthenticationComponent.authenticate(baseUserName, password);
    }
}
