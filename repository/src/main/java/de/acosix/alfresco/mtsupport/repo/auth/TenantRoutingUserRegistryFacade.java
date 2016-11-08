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
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.alfresco.repo.management.subsystems.ActivateableBean;
import org.alfresco.repo.security.sync.NodeDescription;
import org.alfresco.repo.security.sync.UserRegistry;
import org.alfresco.repo.tenant.TenantAdminService;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.repo.tenant.TenantUtil;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.ParameterCheck;
import org.alfresco.util.PropertyCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import de.acosix.alfresco.mtsupport.repo.beans.TenantBeanUtils;
import de.acosix.alfresco.mtsupport.repo.sync.EnhancedUserRegistry;
import de.acosix.alfresco.mtsupport.repo.sync.TenantAwareUserRegistry;
import de.acosix.alfresco.mtsupport.repo.sync.UserAccountInterpreter;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TenantRoutingUserRegistryFacade
        implements TenantAwareUserRegistry, EnhancedUserRegistry, InitializingBean, ApplicationContextAware, ActivateableBean, BeanNameAware
{

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantRoutingUserRegistryFacade.class);

    protected ApplicationContext applicationContext;

    protected String beanName;

    protected TenantAdminService tenantAdminService;

    protected List<String> enabledTenants;

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet()
    {
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

        LOGGER.debug("Checking isActive for enabled tenants (until first active tenant)");
        for (final String tenantDomain : this.enabledTenants)
        {
            if (!isActive.get())
            {
                isActive.set(this.isActive(tenantDomain));
            }
        }
        LOGGER.debug("Component is active: {}", isActive.get());

        return isActive.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isActiveForTenant(final String tenantDomain)
    {
        final boolean isActive = this.isActive(
                tenantDomain == null || TenantService.DEFAULT_DOMAIN.equals(tenantDomain) ? TenantUtil.DEFAULT_TENANT : tenantDomain);
        return isActive;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<NodeDescription> getPersons(final Date modifiedSince)
    {
        final Collection<NodeDescription> results;

        // we always expect the synchronization process to be in a single tenant's context
        final UserRegistry userRegistry = this.getUserRegistryForCurrentDomain();

        if (userRegistry != null)
        {
            results = userRegistry.getPersons(modifiedSince);
        }
        else
        {
            results = Collections.emptyList();
        }
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<NodeDescription> getGroups(final Date modifiedSince)
    {
        final Collection<NodeDescription> results;

        // we always expect the synchronization process to be in a single tenant's context
        final UserRegistry userRegistry = this.getUserRegistryForCurrentDomain();

        if (userRegistry != null)
        {
            results = userRegistry.getGroups(modifiedSince);
        }
        else
        {
            results = Collections.emptyList();
        }
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<String> getPersonNames()
    {
        final Collection<String> results;

        // we always expect the synchronization process to be in a single tenant's context
        final UserRegistry userRegistry = this.getUserRegistryForCurrentDomain();

        if (userRegistry != null)
        {
            results = userRegistry.getPersonNames();
        }
        else
        {
            results = Collections.emptyList();
        }
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<String> getGroupNames()
    {
        final Collection<String> results;

        // we always expect the synchronization process to be in a single tenant's context
        final UserRegistry userRegistry = this.getUserRegistryForCurrentDomain();

        if (userRegistry != null)
        {
            results = userRegistry.getGroupNames();
        }
        else
        {
            results = Collections.emptyList();
        }
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<QName> getPersonMappedProperties()
    {
        final Set<QName> results;

        // we always expect the synchronization process to be in a single tenant's context
        final UserRegistry userRegistry = this.getUserRegistryForCurrentDomain();

        if (userRegistry != null)
        {
            results = userRegistry.getPersonMappedProperties();
        }
        else
        {
            results = Collections.emptySet();
        }
        return results;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public UserAccountInterpreter getUserAccountInterpreter()
    {
        final UserRegistry userRegistry = this.getUserRegistryForCurrentDomain();

        UserAccountInterpreter result;
        if (userRegistry instanceof EnhancedUserRegistry)
        {
            result = ((EnhancedUserRegistry) userRegistry).getUserAccountInterpreter();
        }
        else
        {
            result = null;
        }

        return result;
    }

    protected UserRegistry getUserRegistryForCurrentDomain()
    {
        final String tenantDomain = TenantUtil.getCurrentDomain();
        final UserRegistry userRegistry;
        if (TenantService.DEFAULT_DOMAIN.equals(tenantDomain) && this.isActive(TenantUtil.DEFAULT_TENANT))
        {
            userRegistry = TenantBeanUtils.getBeanForTenant(this.applicationContext, this.beanName, TenantUtil.DEFAULT_TENANT,
                    UserRegistry.class);
        }
        else if (!TenantService.DEFAULT_DOMAIN.equals(tenantDomain) && this.isActive(tenantDomain))
        {
            userRegistry = TenantBeanUtils.getBeanForTenant(this.applicationContext, this.beanName, tenantDomain, UserRegistry.class);
        }
        else
        {
            userRegistry = null;
        }
        return userRegistry;
    }

    protected boolean isActive(final String tenantDomain)
    {
        boolean isActive = false;

        LOGGER.trace("Checking isActive for tenant {}", tenantDomain);
        if (this.enabledTenants.contains(tenantDomain) && (TenantUtil.DEFAULT_TENANT.equals(tenantDomain)
                || (this.tenantAdminService.existsTenant(tenantDomain) && this.tenantAdminService.isEnabledTenant(tenantDomain))))
        {
            final UserRegistry userRegistry = TenantBeanUtils.getBeanForTenant(this.applicationContext, this.beanName, tenantDomain,
                    UserRegistry.class);
            if (userRegistry instanceof ActivateableBean)
            {
                isActive = ((ActivateableBean) userRegistry).isActive();
            }

            LOGGER.trace("Tenant {} configured as active: {}", tenantDomain, isActive);
        }
        else
        {
            LOGGER.trace("Tenant {} does not exist or has not been enabled", tenantDomain);
        }

        return isActive;
    }
}
