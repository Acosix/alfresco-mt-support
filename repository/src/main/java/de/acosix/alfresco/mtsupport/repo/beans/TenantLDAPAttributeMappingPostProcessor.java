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
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.ManagedMap;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TenantLDAPAttributeMappingPostProcessor implements BeanDefinitionRegistryPostProcessor, InitializingBean
{

    private static final String VALUE_NULL = "null";

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantLDAPAttributeMappingPostProcessor.class);

    protected Properties effectiveProperties;

    protected boolean enabled;

    protected String enabledPropertyKey;

    protected String enabledTenantPropertyKey;

    protected String mappingPropertyPrefix = "ldap.synchronization";

    protected String beanName;

    protected String propertyName;

    protected boolean beanReferences;

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet()
    {
        PropertyCheck.mandatory(this, "effectiveProperties", this.effectiveProperties);
        PropertyCheck.mandatory(this, "enabledTenantPropertyKey", this.enabledTenantPropertyKey);
        PropertyCheck.mandatory(this, "mappingPropertyPrefix", this.mappingPropertyPrefix);
        PropertyCheck.mandatory(this, "beanName", this.beanName);
        PropertyCheck.mandatory(this, "propertyName", this.propertyName);
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
     * @param mappingPropertyPrefix
     *            the mappingPropertyPrefix to set
     */
    public void setMappingPropertyPrefix(final String mappingPropertyPrefix)
    {
        this.mappingPropertyPrefix = mappingPropertyPrefix;
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
     * @param beanReferences
     *            the beanReferences to set
     */
    public void setBeanReferences(final boolean beanReferences)
    {
        this.beanReferences = beanReferences;
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
    @SuppressWarnings("unchecked")
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
            else
            {
                final List<String> enabledTenants = Arrays.asList(enabledTenantsProperty.trim().split("\\s*,\\s*"));
                LOGGER.debug("Processing custom LDAP attribute mappings for property {} and enabled tenants {}", this.propertyName,
                        enabledTenants);

                for (final String enabledTenant : enabledTenants)
                {
                    final String tenantBasePrefix = this.mappingPropertyPrefix + "." + enabledTenant + "." + this.propertyName + ".";
                    final String globalBasePrefix = this.mappingPropertyPrefix + "." + this.propertyName + ".";

                    final String tenantMappingPropertiesKey = tenantBasePrefix + "customMappings";
                    final String globalMappingPropertiesKey = globalBasePrefix + "customMappings";

                    final String globalMappingsPropertyString = this.effectiveProperties.getProperty(globalMappingPropertiesKey);
                    final String tenantMappingsPropertyString = this.effectiveProperties.getProperty(tenantMappingPropertiesKey,
                            globalMappingsPropertyString);

                    if (tenantMappingsPropertyString != null && !tenantMappingsPropertyString.trim().isEmpty())
                    {
                        final BeanDefinition beanDefinition = TenantBeanUtils.getBeanDefinitionForTenant(registry, this.beanName,
                                enabledTenant);

                        Map<Object, Object> configuredMapping;
                        final PropertyValue propertyValue = beanDefinition.getPropertyValues().getPropertyValue(this.propertyName);
                        if (propertyValue == null)
                        {
                            configuredMapping = new ManagedMap<>();
                            beanDefinition.getPropertyValues().add(this.propertyName, configuredMapping);
                        }
                        else
                        {
                            final Object value = propertyValue.getValue();
                            if (value instanceof Map<?, ?>)
                            {
                                configuredMapping = (Map<Object, Object>) value;
                            }
                            else
                            {
                                throw new IllegalStateException("Configured property value is not a map");
                            }
                        }

                        final String[] mappingProperties = tenantMappingsPropertyString.trim().split("\\s*,\\s*");
                        for (final String mappingProperty : mappingProperties)
                        {
                            final String globalMappingValuePropertyKey = globalBasePrefix + mappingProperty;
                            final String tenantMappingValuePropertyKey = tenantBasePrefix + mappingProperty;

                            final String globalMappingValue = this.effectiveProperties.getProperty(globalMappingValuePropertyKey);
                            final String tenantMappingValue = this.effectiveProperties.getProperty(tenantMappingValuePropertyKey,
                                    globalMappingValue);

                            final String trimmedMappingValue = tenantMappingValue != null ? tenantMappingValue.trim() : null;
                            if (trimmedMappingValue != null && !trimmedMappingValue.isEmpty())
                            {
                                if (this.beanReferences)
                                {
                                    if (VALUE_NULL.equals(trimmedMappingValue))
                                    {
                                        configuredMapping.remove(mappingProperty);
                                    }
                                    else
                                    {
                                        configuredMapping.put(mappingProperty, new RuntimeBeanReference(trimmedMappingValue));
                                    }
                                }
                                else
                                {
                                    if (VALUE_NULL.equals(trimmedMappingValue))
                                    {
                                        configuredMapping.put(mappingProperty, null);
                                    }
                                    else
                                    {
                                        configuredMapping.put(mappingProperty, trimmedMappingValue);
                                    }
                                }
                            }
                        }
                    }
                }
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
