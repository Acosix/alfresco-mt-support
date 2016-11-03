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
import java.util.Map;
import java.util.Properties;

import org.alfresco.util.PropertyCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.ManagedMap;

/**
 * Instances of this class may be used to dynamically emit maps of property values keyed by tenant domains. This is typically useful to fill
 * {@link Map}-based bean properties of global beans that handle tenant-specific checks internally.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TenantPropertyMapEmitter implements BeanDefinitionRegistryPostProcessor, InitializingBean
{

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantPropertyMapEmitter.class);

    protected Properties effectiveProperties;

    protected boolean enabled;

    protected String enabledPropertyKey;

    protected String enabledTenantPropertyKey;

    protected String beanName;

    protected String propertyName;

    protected String propertyPattern;

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet()
    {
        PropertyCheck.mandatory(this, "effectiveProperties", this.effectiveProperties);
        PropertyCheck.mandatory(this, "enabledTenantPropertyKey", this.enabledTenantPropertyKey);
        PropertyCheck.mandatory(this, "beanName", this.beanName);
        PropertyCheck.mandatory(this, "propertyName", this.propertyName);
        PropertyCheck.mandatory(this, "propertyPattern", this.propertyPattern);
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
     * @param enabled
     *            the enabled to set
     */
    public void setEnabled(final boolean enabled)
    {
        this.enabled = enabled;
    }

    /**
     * @param enabledPropertyKey
     *            the enabledPropertyKey to set
     */
    public void setEnabledPropertyKey(final String enabledPropertyKey)
    {
        this.enabledPropertyKey = enabledPropertyKey;
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
     * @param beanName
     *            the beanName to set
     */
    public void setBeanName(final String beanName)
    {
        this.beanName = beanName;
    }

    /**
     * @param propertyName
     *            the propertyName to set
     */
    public void setPropertyName(final String propertyName)
    {
        this.propertyName = propertyName;
    }

    /**
     * @param propertyPattern
     *            the propertyPattern to set
     */
    public void setPropertyPattern(final String propertyPattern)
    {
        this.propertyPattern = propertyPattern;
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
        if (this.isEnabled())
        {
            final String enabledTenantsProperty = this.effectiveProperties.getProperty(this.enabledTenantPropertyKey);
            if (enabledTenantsProperty == null || enabledTenantsProperty.trim().isEmpty())
            {
                LOGGER.debug("No tenants have been defined as enabled");
            }
            else if (registry.containsBeanDefinition(this.beanName))
            {
                final List<String> enabledTenants = Arrays.asList(enabledTenantsProperty.trim().split("\\s*,\\s*"));
                LOGGER.debug("Generating value for map property {} on bean {} for enabled tenants {}", this.propertyName, this.beanName,
                        enabledTenants);

                final BeanDefinition beanDefinition = registry.getBeanDefinition(this.beanName);

                final Map<String, String> values = new ManagedMap<>();

                final String[] fragments = this.propertyPattern.split(TenantBeanUtils.TENANT_PLACEHOLDER_IN_PLACEHOLDER);

                for (final String tenant : enabledTenants)
                {
                    final StringBuilder valueBuilder = new StringBuilder();
                    valueBuilder.append("${");
                    valueBuilder.append(fragments[0]);
                    valueBuilder.append('.');
                    valueBuilder.append(tenant);
                    valueBuilder.append('.');
                    valueBuilder.append(fragments[1]);
                    valueBuilder.append(':');
                    valueBuilder.append("${");
                    valueBuilder.append(fragments[0]);
                    valueBuilder.append('.');
                    valueBuilder.append(fragments[1]);
                    valueBuilder.append("}}");

                    values.put(tenant, valueBuilder.toString());
                }

                beanDefinition.getPropertyValues().add(this.propertyName, values);
            }
            else
            {
                LOGGER.warn("No template bean defined for {}", this.beanName);
            }
        }
    }

    protected boolean isEnabled()
    {
        boolean result = this.enabled;

        if (this.enabledPropertyKey != null)
        {
            final String enabledProperty = this.effectiveProperties.getProperty(this.enabledPropertyKey);
            result |= Boolean.parseBoolean(enabledProperty);
        }

        return result;
    }
}
