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

import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.ldap.LDAPAuthenticationComponentImpl;
import org.alfresco.repo.tenant.TenantContextHolder;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.repo.tenant.TenantUtil;
import org.alfresco.util.EqualsHelper;
import org.alfresco.util.Pair;
import org.alfresco.util.PropertyCheck;

import net.sf.acegisecurity.Authentication;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TenantAwareLDAPAuthenticationComponent extends LDAPAuthenticationComponentImpl
{

    protected TenantService tenantService;

    protected String tenantDomain;

    protected boolean stripTenantDomainForAuthentication;

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet() throws Exception
    {
        super.afterPropertiesSet();

        PropertyCheck.mandatory(this, "tenantService", this.tenantService);
        PropertyCheck.mandatory(this, "tenantDomain", this.tenantDomain);
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
     * @param tenantDomain
     *            the tenantDomain to set
     */
    public void setTenantDomain(final String tenantDomain)
    {
        this.tenantDomain = tenantDomain;
    }

    /**
     * @param stripTenantDomainForAuthentication
     *            the stripTenantDomainForAuthentication to set
     */
    public void setStripTenantDomainForAuthentication(final boolean stripTenantDomainForAuthentication)
    {
        this.stripTenantDomainForAuthentication = stripTenantDomainForAuthentication;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void authenticateImpl(final String userName, final char[] password) throws AuthenticationException
    {
        String baseUserName;

        final Pair<String, String> userTenant = AuthenticationUtil.getUserTenant(userName);
        final String tenantDomain = userTenant.getSecond();
        if (this.stripTenantDomainForAuthentication && EqualsHelper.nullSafeEquals(this.tenantDomain, tenantDomain))
        {
            baseUserName = this.tenantService.getBaseNameUser(userName);
        }
        else
        {
            baseUserName = userName;
        }

        super.authenticateImpl(baseUserName, password);
    }

    @Override
    public Authentication setCurrentUser(final String authenticatedUserName) throws AuthenticationException
    {
        String domainUser = authenticatedUserName;
        if (!EqualsHelper.nullSafeEquals(this.tenantDomain, TenantUtil.DEFAULT_TENANT))
        {
            TenantContextHolder.setTenantDomain(this.tenantDomain);

            final String domain = this.tenantService.getDomain(authenticatedUserName);
            if (!EqualsHelper.nullSafeEquals(domain, this.tenantDomain))
            {
                domainUser = this.tenantService.getDomainUser(authenticatedUserName, this.tenantDomain);
            }
        }

        final Authentication authentication = super.setCurrentUser(domainUser);
        return authentication;
    }
}
