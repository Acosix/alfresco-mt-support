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
package de.acosix.alfresco.mtsupport.repo.beans;

import org.alfresco.util.ParameterCheck;
import org.springframework.context.ApplicationContext;

/**
 * This class bundles small externalised utility operations that do not fit in any other class.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public final class TenantBeanUtils
{

    public static final String TENANT_BEAN_NAME_PATTERN = ".tenant-";

    public static final String TENANT_PLACEHOLDER = "tenant";

    public static final String TENANT_PLACEHOLDER_IN_PLACEHOLDER = ".thisTenant.";

    private TenantBeanUtils()
    {
        // NO-OP
    }

    /**
     * Retrieves a specific bean for a specific tenant from the provided application context. This operation should only be used when each
     * tenant domain is backed by its own implementation bean for a feature and calls need to be delegated according to the tenant domain.
     *
     * @param applicationContext
     *            the application context from which to retrieve the bean
     * @param baseBeanName
     *            the base bean name
     * @param tenantDomain
     *            the tenant domain for which to retrieve the bean
     * @return the bean
     */
    public static Object getBeanForTenant(final ApplicationContext applicationContext, final String baseBeanName, final String tenantDomain)
    {
        ParameterCheck.mandatory("applicationContext", applicationContext);
        ParameterCheck.mandatoryString("baseBeanName", baseBeanName);
        ParameterCheck.mandatoryString("tenantDomain", tenantDomain);

        final String expectedBeanName = baseBeanName + TENANT_BEAN_NAME_PATTERN + tenantDomain;
        final Object bean = applicationContext.getBean(expectedBeanName);
        return bean;
    }

    /**
     * Retrieves a specific bean for a specific tenant from the provided application context. This operation should only be used when each
     * tenant domain is backed by its own implementation bean for a feature and calls need to be delegated according to the tenant domain.
     *
     * @param applicationContext
     *            the application context from which to retrieve the bean
     * @param baseBeanName
     *            the base bean name
     * @param tenantDomain
     *            the tenant domain for which to retrieve the bean
     * @param expectedType
     *            the expected class or interface to which the bean must conform
     * @return the bean
     */
    public static <T> T getBeanForTenant(final ApplicationContext applicationContext, final String baseBeanName, final String tenantDomain,
            final Class<T> expectedType)
    {
        ParameterCheck.mandatory("applicationContext", applicationContext);
        ParameterCheck.mandatoryString("baseBeanName", baseBeanName);
        ParameterCheck.mandatoryString("tenantDomain", tenantDomain);
        ParameterCheck.mandatory("expectedType", expectedType);

        final String expectedBeanName = baseBeanName + TENANT_BEAN_NAME_PATTERN + tenantDomain;
        final T bean = applicationContext.getBean(expectedBeanName, expectedType);
        return bean;
    }
}
