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
package de.acosix.alfresco.mtsupport.repo.subsystems;

import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionVisitor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.util.StringValueResolver;

import de.acosix.alfresco.mtsupport.repo.beans.TenantBeanUtils;

/**
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TenantAwareSubsystemPlaceholderConfigurer extends PropertyPlaceholderConfigurer
{

    // since this bean is likely to be used inside of subsystems there is a chance the logging framework has already been configured
    private static final Logger LOGGER = LoggerFactory.getLogger(TenantAwareSubsystemPlaceholderConfigurer.class);

    protected static final ThreadLocal<String> TENANT_CONTEXT = new ThreadLocal<>();

    protected String beanName;

    protected BeanFactory beanFactory;

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void setBeanName(final String beanName)
    {
        super.setBeanName(beanName);
        this.beanName = beanName;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void setBeanFactory(final BeanFactory beanFactory)
    {
        super.setBeanFactory(beanFactory);
        this.beanFactory = beanFactory;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doProcessProperties(final ConfigurableListableBeanFactory beanFactoryToProcess, final StringValueResolver valueResolver)
    {
        final BeanDefinitionVisitor visitor = new BeanDefinitionVisitor(valueResolver);

        final String[] beanNames = beanFactoryToProcess.getBeanDefinitionNames();
        for (final String curName : beanNames)
        {
            // Check that we're not parsing our own bean definition,
            // to avoid failing on unresolvable placeholders in properties file locations.
            if (!(curName.equals(this.beanName) && beanFactoryToProcess.equals(this.beanFactory)))
            {
                final String tenantDomain;
                if (curName.contains(TenantBeanUtils.TENANT_BEAN_NAME_PATTERN))
                {
                    tenantDomain = curName.substring(
                            curName.indexOf(TenantBeanUtils.TENANT_BEAN_NAME_PATTERN) + TenantBeanUtils.TENANT_BEAN_NAME_PATTERN.length());
                    LOGGER.debug("[{}] Processing bean {} for tenant domain {}", this.beanName, curName, tenantDomain);
                }
                else
                {
                    LOGGER.debug("[{}] Processing bean {} without tenant domain", this.beanName, curName);
                    tenantDomain = null;
                }

                final BeanDefinition bd = beanFactoryToProcess.getBeanDefinition(curName);
                TENANT_CONTEXT.set(tenantDomain);
                try
                {
                    visitor.visitBeanDefinition(bd);
                }
                catch (final Exception ex)
                {
                    throw new BeanDefinitionStoreException(bd.getResourceDescription(), curName, ex.getMessage());
                }
                finally
                {
                    TENANT_CONTEXT.remove();
                }
            }
        }
        LOGGER.debug("[{}] Completed processing all beans", this.beanName);

        // New in Spring 2.5: resolve placeholders in alias target names and aliases as well.
        beanFactoryToProcess.resolveAliases(valueResolver);

        // New in Spring 3.0: resolve placeholders in embedded values such as annotation attributes.
        beanFactoryToProcess.addEmbeddedValueResolver(valueResolver);

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String resolvePlaceholder(final String placeholder, final Properties props, final int systemPropertiesMode)
    {
        String resolved = null;
        if (TenantBeanUtils.TENANT_PLACEHOLDER.equals(placeholder))
        {
            resolved = TENANT_CONTEXT.get();
        }

        // placeholder contains the expected placeholder, perform resolution for tenant-specific and generic default variant of placeholder
        if (TENANT_CONTEXT.get() != null && placeholder.contains(TenantBeanUtils.TENANT_PLACEHOLDER_IN_PLACEHOLDER))
        {
            final String tenantDomain = TENANT_CONTEXT.get();
            LOGGER.debug("[{}] Processing placeholder {} for tenant domain", this.beanName, placeholder, tenantDomain);

            final StringBuilder tenantReplacementBuilder = new StringBuilder();
            tenantReplacementBuilder.append(".");
            tenantReplacementBuilder.append(tenantDomain);
            tenantReplacementBuilder.append(".");

            final String placeholderWithSpecificTenant = placeholder.replace(TenantBeanUtils.TENANT_PLACEHOLDER_IN_PLACEHOLDER,
                    tenantReplacementBuilder.toString());

            resolved = super.resolvePlaceholder(placeholderWithSpecificTenant, props, systemPropertiesMode);

            if (resolved == null)
            {
                tenantReplacementBuilder.delete(1, tenantReplacementBuilder.length());

                final String placeholderForDefaultProperty = placeholder.replace(TenantBeanUtils.TENANT_PLACEHOLDER_IN_PLACEHOLDER,
                        tenantReplacementBuilder.toString());

                resolved = super.resolvePlaceholder(placeholderForDefaultProperty, props, systemPropertiesMode);

                if (resolved != null)
                {
                    LOGGER.debug("[{}] Placeholder {} resolved to value {} from default configuration", this.beanName, placeholder,
                            resolved);
                }
                else
                {
                    LOGGER.debug("[{}] Placeholder {} could not be resolved against tenant or default configuration", this.beanName,
                            placeholder);
                }
            }
            else
            {
                LOGGER.debug("[{}] Placeholder {} resolved specific value {} from tenant configuration", this.beanName, placeholder,
                        resolved);
            }
        }

        // fall back
        if (resolved == null)
        {
            resolved = super.resolvePlaceholder(placeholder, props, systemPropertiesMode);
        }

        return resolved;
    }
}
