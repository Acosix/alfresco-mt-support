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

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.alfresco.util.PropertyCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

/**
 * Instances of this class may be used to dynamically emit new bean definitions for tenant-specific bean instances based on pre-defined
 * template bean definitions and a set of enabled tenants. Effectively instances of this class simply duplicate the definition of the
 * template and register them as a singleton bean under a unique name using {@link TenantBeanUtils#TENANT_BEAN_NAME_PATTERN a defined
 * pattern} for the name suffix.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TemplatedTenantBeanEmitter implements BeanDefinitionRegistryPostProcessor, InitializingBean
{

    private static final Logger LOGGER = LoggerFactory.getLogger(TemplatedTenantBeanEmitter.class);

    protected Properties effectiveProperties;

    protected String enabledTenantPropertyKey;

    protected List<String> beanNames;

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet()
    {
        PropertyCheck.mandatory(this, "effectiveProperties", this.effectiveProperties);
        PropertyCheck.mandatory(this, "enabledTenantPropertyKey", this.enabledTenantPropertyKey);
        PropertyCheck.mandatory(this, "beanNames", this.beanNames);
    }

    /**
     * @param effectiveProperties
     *            the effectiveProperties to set
     */
    public void setEffectiveProperties(final Properties effectiveProperties)
    {
        this.effectiveProperties = effectiveProperties;
    }

    /**
     * @param enabledTenantPropertyKey
     *            the enabledTenantPropertyKey to set
     */
    public void setEnabledTenantPropertyKey(final String enabledTenantPropertyKey)
    {
        this.enabledTenantPropertyKey = enabledTenantPropertyKey;
    }

    /**
     * @param beanNames
     *            the beanNames to set
     */
    public void setBeanNames(final List<String> beanNames)
    {
        this.beanNames = beanNames;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) throws BeansException
    {
        // NO-OP

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void postProcessBeanDefinitionRegistry(final BeanDefinitionRegistry registry) throws BeansException
    {
        final String enabledTenantsProperty = this.effectiveProperties.getProperty(this.enabledTenantPropertyKey);
        if (enabledTenantsProperty == null || enabledTenantsProperty.trim().isEmpty())
        {
            LOGGER.debug("No tenants have been defined as enabled");
        }
        else
        {
            final List<String> enabledTenants = Arrays.asList(enabledTenantsProperty.trim().split("\\s*,\\s*"));
            LOGGER.debug("Processing beans {} for enabled tenants {}", this.beanNames, enabledTenants);

            for (final String beanName : this.beanNames)
            {
                LOGGER.debug("Processing {}", beanName);
                final String templateBeanName = beanName + TenantBeanUtils.TENANT_BEAN_TEMPLATE_SUFFIX;
                if (registry.containsBeanDefinition(templateBeanName))
                {
                    final BeanDefinition beanDefinition = registry.getBeanDefinition(templateBeanName);

                    if (beanDefinition instanceof AbstractBeanDefinition)
                    {
                        for (final String tenant : enabledTenants)
                        {
                            final AbstractBeanDefinition cloneBeanDefinition = ((AbstractBeanDefinition) beanDefinition)
                                    .cloneBeanDefinition();
                            cloneBeanDefinition.setScope(AbstractBeanDefinition.SCOPE_DEFAULT);
                            final String tenantBeanName = beanName + TenantBeanUtils.TENANT_BEAN_NAME_PATTERN + tenant;

                            LOGGER.debug("Adding clone of {} for tenant {}", templateBeanName, tenant);
                            registry.registerBeanDefinition(tenantBeanName, cloneBeanDefinition);
                        }
                    }
                }
                else
                {
                    LOGGER.warn("No template bean defined for {}", beanName);
                }
            }
        }
    }

}
