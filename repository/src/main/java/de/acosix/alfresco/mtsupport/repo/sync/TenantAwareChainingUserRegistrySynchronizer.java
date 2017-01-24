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
package de.acosix.alfresco.mtsupport.repo.sync;

import java.io.IOException;
import java.io.Serializable;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.alfresco.repo.admin.SysAdminParams;
import org.alfresco.repo.batch.BatchProcessor;
import org.alfresco.repo.dictionary.constraint.NameChecker;
import org.alfresco.repo.lock.JobLockService;
import org.alfresco.repo.lock.JobLockService.JobLockRefreshCallback;
import org.alfresco.repo.lock.LockAcquisitionException;
import org.alfresco.repo.management.subsystems.ActivateableBean;
import org.alfresco.repo.management.subsystems.ChildApplicationContextManager;
import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.sync.ChainingUserRegistrySynchronizer;
import org.alfresco.repo.security.sync.ChainingUserRegistrySynchronizerStatus;
import org.alfresco.repo.security.sync.NodeDescription;
import org.alfresco.repo.security.sync.SyncStatus;
import org.alfresco.repo.security.sync.SynchronizeDiagnostic;
import org.alfresco.repo.security.sync.SynchronizeDiagnosticImpl;
import org.alfresco.repo.security.sync.SynchronizeDirectoryEndEvent;
import org.alfresco.repo.security.sync.SynchronizeDirectoryStartEvent;
import org.alfresco.repo.security.sync.SynchronizeEndEvent;
import org.alfresco.repo.security.sync.SynchronizeStartEvent;
import org.alfresco.repo.security.sync.TestableChainingUserRegistrySynchronizer;
import org.alfresco.repo.security.sync.UserRegistry;
import org.alfresco.repo.security.sync.UserRegistrySynchronizer;
import org.alfresco.repo.tenant.TenantContextHolder;
import org.alfresco.repo.tenant.TenantService;
import org.alfresco.repo.tenant.TenantUtil;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport;
import org.alfresco.repo.transaction.AlfrescoTransactionSupport.TxnReadState;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.cmr.attributes.AttributeService;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.AuthorityType;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.util.EqualsHelper;
import org.alfresco.util.Pair;
import org.alfresco.util.ParameterCheck;
import org.alfresco.util.PropertyCheck;
import org.apache.commons.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.extensions.surf.util.AbstractLifecycleBean;
import org.springframework.extensions.surf.util.I18NUtil;

/**
 * This class completely re-implements the user and groups synchronization logic provided by {@link ChainingUserRegistrySynchronizer} and
 * enhances it to support multi-tenancy use cases. The default component in out-of-the-box Alfresco is impossible to customise or in any way
 * adapt to handling multi-tenancy, as it is a single monolithic mess.
 *
 * All operations defined by the interfaces {@link UserRegistrySynchronizer}, {@link ChainingUserRegistrySynchronizerStatus} and
 * {@link TestableChainingUserRegistrySynchronizer} will always
 * be performed for the {@link TenantContextHolder#getTenantDomain() current tenant}. This ensures that by default the behaviour is
 * equivalent to the default implementation. Internally, the implementation will consistently use the current tenant to keep track e.g. of
 * last synchronisation dates so differential synchronisations can be performed on a per-tenant basis.
 * Internal job locking will use the tenant as a further differentiator, so that synchronisation of different tenants can be performed on
 * multiple nodes to improve scaling.
 *
 * Since existing default {@link UserRegistry user registry} implementations may not be tenant-aware this implementation will only use
 * registries especially {@link TenantAwareUserRegistry marked to be multi-tenancy aware} when synchronising users and groups in any tenant
 * other than the default tenant.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class TenantAwareChainingUserRegistrySynchronizer extends AbstractLifecycleBean implements UserRegistrySynchronizer,
        ChainingUserRegistrySynchronizerStatus, TestableChainingUserRegistrySynchronizer, InitializingBean, ApplicationEventPublisherAware
{

    /**
     * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
     */
    @FunctionalInterface
    public static interface ComponentLookupCallback
    {

        /**
         * Retrieves the matching component from an enclosing {@link TenantAwareChainingUserRegistrySynchronizer} instance.
         *
         * @param name
         *            the name of the component
         * @return the component
         * @throws IllegalStateException
         *             if the requested component is not available
         */

        Object getComponent(String name);

        /**
         * Retrieves the matching component from an enclosing {@link TenantAwareChainingUserRegistrySynchronizer} instance.
         *
         * @param <T>
         *            the expected base type of the component
         * @param name
         *            the name of the component
         * @param iface
         *            the interface of the component
         * @return the service
         * @throws IllegalStateException
         *             if the requested component is not available
         */
        default <T> T getComponent(final String name, final Class<T> iface)
        {
            final Object service = this.getComponent(name);
            return iface.cast(service);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantAwareChainingUserRegistrySynchronizer.class);

    private static final long LOCK_TTL = 1000 * 60 * 2;

    private static final QName DEFAULT_LOCK_QNAME = QName.createQName(NamespaceService.SYSTEM_MODEL_1_0_URI,
            "ChainingUserRegistrySynchronizer");

    public static final int USER_REGISTRY_ENTITY_BATCH_SIZE = 20;

    public static final String ROOT_ATTRIBUTE_PATH = ".ChainingUserRegistrySynchronizer";

    public static final String ROOT_MT_ATTRIBUTE_PATH = ".TenantAwareChainingUserRegistrySynchronizer";

    public static final String GROUP_LAST_MODIFIED_ATTRIBUTE = "GROUP";

    public static final String PERSON_LAST_MODIFIED_ATTRIBUTE = "PERSON";

    public static final String STATUS_ATTRIBUTE = "STATUS";

    public static final String LAST_ERROR_ATTRIBUTE = "LAST_ERROR";

    public static final String START_TIME_ATTRIBUTE = "START_TIME";

    public static final String END_TIME_ATTRIBUTE = "END_TIME";

    public static final String SERVER_ATTRIBUTE = "LAST_RUN_HOST";

    public static final String SUMMARY_ATTRIBUTE = "SUMMARY";

    protected ApplicationContext applicationContext;

    protected ChildApplicationContextManager applicationContextManager;

    protected String userRegistrySourceBeanName;

    protected AuthorityService authorityService;

    protected NodeService nodeService;

    protected ContentService contentService;

    protected PersonService personService;

    protected AttributeService attributeService;

    protected TransactionService transactionService;

    protected JobLockService jobLockService;

    protected ApplicationEventPublisher applicationEventPublisher;

    protected boolean syncWhenMissingPeopleLogIn = true;

    protected boolean syncOnStartup = true;

    protected boolean autoCreatePeopleOnLogin = true;

    protected int loggingInterval = 100;

    protected int workerThreads = 2;

    protected MBeanServerConnection mbeanServer;

    protected boolean syncDelete = true;

    protected boolean allowDeletions = true;

    protected NameChecker nameChecker;

    protected SysAdminParams sysAdminParams;

    // introduced with 5.2 - adapted to be tenant aware
    protected Map<String, String> externalUserControl = Collections.emptyMap();

    protected Map<String, String> externalUserControlSubsystemName = Collections.emptyMap();

    protected TenantService tenantService;

    public void init()
    {
        // NO-OP - only exists for compatibility with default class
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet()
    {
        PropertyCheck.mandatory(this, "applicationContextManager", this.applicationContextManager);
        PropertyCheck.mandatory(this, "userRegistrySourceBeanName", this.userRegistrySourceBeanName);
        PropertyCheck.mandatory(this, "authorityService", this.authorityService);
        PropertyCheck.mandatory(this, "nodeService", this.nodeService);
        PropertyCheck.mandatory(this, "contentService", this.contentService);
        PropertyCheck.mandatory(this, "personService", this.personService);
        PropertyCheck.mandatory(this, "attributeService", this.attributeService);
        PropertyCheck.mandatory(this, "transactionService", this.transactionService);
        PropertyCheck.mandatory(this, "jobLockService", this.jobLockService);
        PropertyCheck.mandatory(this, "applicationEventPublisher", this.applicationEventPublisher);
        PropertyCheck.mandatory(this, "nameChecker", this.nameChecker);
        PropertyCheck.mandatory(this, "sysAdminParams", this.sysAdminParams);
        PropertyCheck.mandatory(this, "tenantService", this.tenantService);

        // likely not available in Community Edition servers
        if (this.mbeanServer == null)
        {
            if (this.applicationContext.containsBean("alfrescoMBeanServer"))
            {
                this.mbeanServer = (MBeanServerConnection) this.applicationContext.getBean("alfrescoMBeanServer");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setApplicationContext(final ApplicationContext applicationContext)
    {
        super.setApplicationContext(applicationContext);
        this.applicationContext = applicationContext;
    }

    /**
     * @see ChainingUserRegistrySynchronizer#setApplicationContextManager(ChildApplicationContextManager)
     */
    public void setApplicationContextManager(final ChildApplicationContextManager applicationContextManager)
    {
        this.applicationContextManager = applicationContextManager;
    }

    /**
     * Sets the bean name for user registry beans to look up via {@link #setApplicationContextManager(ChildApplicationContextManager)
     * subsystem application contexts}.
     *
     * @param userRegistrySourceBeanName
     *            the userRegistrySourceBeanName to set
     */
    public void setUserRegistrySourceBeanName(final String userRegistrySourceBeanName)
    {
        this.userRegistrySourceBeanName = userRegistrySourceBeanName;
    }

    /**
     * Sets the bean name for user registry beans to look up via {@link #setApplicationContextManager(ChildApplicationContextManager)
     * subsystem application contexts}. This operation is only an alias to {@link #setUserRegistrySourceBeanName(String)
     * setUserRegistrySourceBeanName} for compatibility with out-of-the-box Alfresco bean definitions.
     *
     * @see ChainingUserRegistrySynchronizer#setSourceBeanName(String)
     *
     * @param sourceBeanName
     *            the userRegistrySourceBeanName to set
     */
    public void setSourceBeanName(final String sourceBeanName)
    {
        this.userRegistrySourceBeanName = sourceBeanName;
    }

    /**
     * @param authorityService
     *            the authorityService to set
     */
    public void setAuthorityService(final AuthorityService authorityService)
    {
        this.authorityService = authorityService;
    }

    /**
     * @param nodeService
     *            the nodeService to set
     */
    public void setNodeService(final NodeService nodeService)
    {
        this.nodeService = nodeService;
    }

    /**
     * @param contentService
     *            the contentService to set
     */
    public void setContentService(final ContentService contentService)
    {
        this.contentService = contentService;
    }

    /**
     * @param personService
     *            the personService to set
     */
    public void setPersonService(final PersonService personService)
    {
        this.personService = personService;
    }

    /**
     * @param attributeService
     *            the attributeService to set
     */
    public void setAttributeService(final AttributeService attributeService)
    {
        this.attributeService = attributeService;
    }

    /**
     * @param transactionService
     *            the transactionService to set
     */
    public void setTransactionService(final TransactionService transactionService)
    {
        this.transactionService = transactionService;
    }

    /**
     * @see ChainingUserRegistrySynchronizer#setJobLockService(JobLockService)
     */
    public void setJobLockService(final JobLockService jobLockService)
    {
        this.jobLockService = jobLockService;
    }

    /**
     * @param syncWhenMissingPeopleLogIn
     *            the syncWhenMissingPeopleLogIn to set
     */
    public void setSyncWhenMissingPeopleLogIn(final boolean syncWhenMissingPeopleLogIn)
    {
        this.syncWhenMissingPeopleLogIn = syncWhenMissingPeopleLogIn;
    }

    /**
     * @param syncOnStartup
     *            the syncOnStartup to set
     */
    public void setSyncOnStartup(final boolean syncOnStartup)
    {
        this.syncOnStartup = syncOnStartup;
    }

    /**
     * @param autoCreatePeopleOnLogin
     *            the autoCreatePeopleOnLogin to set
     */
    public void setAutoCreatePeopleOnLogin(final boolean autoCreatePeopleOnLogin)
    {
        this.autoCreatePeopleOnLogin = autoCreatePeopleOnLogin;
    }

    /**
     * @param loggingInterval
     *            the loggingInterval to set
     */
    public void setLoggingInterval(final int loggingInterval)
    {
        if (loggingInterval <= 0)
        {
            throw new IllegalArgumentException("loggingInterval must be a positive integer");
        }
        this.loggingInterval = loggingInterval;
    }

    /**
     * @param workerThreads
     *            the workerThreads to set
     */
    public void setWorkerThreads(final int workerThreads)
    {
        if (workerThreads <= 0)
        {
            throw new IllegalArgumentException("workerThreads must be a positive integer");
        }
        this.workerThreads = workerThreads;
    }

    /**
     * @param allowDeletions
     *            the allowDeletions to set
     */
    public void setAllowDeletions(final boolean allowDeletions)
    {
        this.allowDeletions = allowDeletions;
    }

    /**
     * @param syncDelete
     *            the syncDelete to set
     */
    public void setSyncDelete(final boolean syncDelete)
    {
        this.syncDelete = syncDelete;
    }

    /**
     * @param nameChecker
     *            the nameChecker to set
     */
    public void setNameChecker(final NameChecker nameChecker)
    {
        this.nameChecker = nameChecker;
    }

    /**
     * @param sysAdminParams
     *            the sysAdminParams to set
     */
    public void setSysAdminParams(final SysAdminParams sysAdminParams)
    {
        this.sysAdminParams = sysAdminParams;
    }

    /**
     * @param externalUserControl
     *            the externalUserControl to set
     */
    public void setExternalUserControl(final Map<String, String> externalUserControl)
    {
        this.externalUserControl = externalUserControl;
    }

    /**
     * @param externalUserControlSubsystemName
     *            the externalUserControlSubsystemName to set
     */
    public void setExternalUserControlSubsystemName(final Map<String, String> externalUserControlSubsystemName)
    {
        this.externalUserControlSubsystemName = externalUserControlSubsystemName;
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
     * {@inheritDoc}
     */
    @Override
    public void setApplicationEventPublisher(final ApplicationEventPublisher applicationEventPublisher)
    {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public SynchronizeDiagnostic testSynchronize(final String authenticatorName)
    {
        ParameterCheck.mandatoryString("authenticatorName", authenticatorName);

        final SynchronizeDiagnosticImpl ret = new SynchronizeDiagnosticImpl();
        final Collection<String> instanceIds = this.applicationContextManager.getInstanceIds();

        if (!instanceIds.contains(authenticatorName))
        {
            final Object params[] = { authenticatorName };
            throw new AuthenticationException("authentication.err.validation.authenticator.notfound", params);
        }

        final ApplicationContext context = this.applicationContextManager.getApplicationContext(authenticatorName);
        final UserRegistry plugin = (UserRegistry) context.getBean(this.userRegistrySourceBeanName);

        final boolean active = this.checkPluginIsActive(plugin);

        ret.setActive(active);

        final Date groupLastModified = this.getMostRecentUpdateTime(GROUP_LAST_MODIFIED_ATTRIBUTE, authenticatorName, false);
        final Date personLastModified = this.getMostRecentUpdateTime(PERSON_LAST_MODIFIED_ATTRIBUTE, authenticatorName, false);
        ret.setGroupLastSynced(groupLastModified);
        ret.setPersonLastSynced(personLastModified);

        if (active)
        {
            this.testSynchronize(plugin, ret);
        }

        return ret;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void synchronize(final boolean forceUpdate, final boolean isFullSync)
    {
        // Don't proceed with the sync if the repository is read only
        if (this.transactionService.isReadOnly())
        {
            LOGGER.warn("Unable to proceed with user registry synchronization. Repository is read only.");
        }
        else
        {
            this.synchronize(forceUpdate, isFullSync, true);
        }
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public Set<QName> getPersonMappedProperties(final String username)
    {
        final Set<String> authorityZones = this.authorityService.getAuthorityZones(username);
        Set<QName> mappedProperties = Collections.emptySet();

        if (authorityZones != null)
        {
            final Collection<String> instanceIds = this.applicationContextManager.getInstanceIds();
            for (final String id : instanceIds)
            {
                final String zoneId = AuthorityService.ZONE_AUTH_EXT_PREFIX + id;
                if (authorityZones.contains(zoneId))
                {
                    final ApplicationContext context = this.applicationContextManager.getApplicationContext(id);
                    final UserRegistry plugin;

                    try
                    {
                        plugin = (UserRegistry) context.getBean(this.userRegistrySourceBeanName);
                    }
                    catch (final RuntimeException re)
                    {
                        LOGGER.debug("Subsystem {} cannot be used in synchronisation", re);
                        continue;
                    }

                    final boolean active = this.checkPluginIsActive(plugin);

                    if (active)
                    {
                        mappedProperties = plugin.getPersonMappedProperties();
                    }
                }
            }
        }

        return mappedProperties;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean createMissingPerson(final String userName)
    {
        ParameterCheck.mandatoryString("userName", userName);

        boolean personCreated = false;
        final String baseNameUser = this.tenantService.getBaseNameUser(userName);
        if (!baseNameUser.equals(AuthenticationUtil.getSystemUserName()))
        {
            if (this.syncWhenMissingPeopleLogIn)
            {
                try
                {
                    this.synchronize(false, false, false);
                }
                catch (final Exception e)
                {
                    // We don't want to fail the whole login if we can help it
                    LOGGER.warn("User authenticated but failed to sync with user registry", e);
                }

                if (this.personService.personExists(userName))
                {
                    personCreated = true;
                }
            }

            if (!personCreated && this.autoCreatePeopleOnLogin && this.personService.createMissingPeople())
            {
                final AuthorityType authorityType = AuthorityType.getAuthorityType(userName);
                if (authorityType == AuthorityType.USER)
                {
                    this.personService.getPerson(userName);
                    personCreated = true;
                }
            }
        }

        return personCreated;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Date getSyncStartTime()
    {
        final Long start = (Long) this.doGetAttribute(true, START_TIME_ATTRIBUTE);

        final Date lastStart = start.longValue() == -1 ? null : new Date(start.longValue());
        return lastStart;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Date getSyncEndTime()
    {
        final Long end = (Long) this.doGetAttribute(true, END_TIME_ATTRIBUTE);

        final Date lastEnd = end.longValue() == -1 ? null : new Date(end.longValue());
        return lastEnd;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLastErrorMessage()
    {
        final String error = (String) this.doGetAttribute(true, LAST_ERROR_ATTRIBUTE);
        return error;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLastRunOnServer()
    {
        final String server = (String) this.doGetAttribute(true, SERVER_ATTRIBUTE);
        return server;
    }

    @Override
    public String getSynchronizationStatus()
    {
        final String status = (String) this.doGetAttribute(true, STATUS_ATTRIBUTE);
        return status;

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getSynchronizationStatus(final String zoneId)
    {
        final String status = (String) this.doGetAttribute(1, STATUS_ATTRIBUTE, zoneId);
        return status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Date getSynchronizationLastUserUpdateTime(final String id)
    {
        final Date lastUserUpdate = this.getMostRecentUpdateTime(PERSON_LAST_MODIFIED_ATTRIBUTE, id, false);
        return lastUserUpdate;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Date getSynchronizationLastGroupUpdateTime(final String id)
    {
        final Date lastGroupUpdate = this.getMostRecentUpdateTime(GROUP_LAST_MODIFIED_ATTRIBUTE, id, false);
        return lastGroupUpdate;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getSynchronizationLastError(final String zoneId)
    {
        final String status = (String) this.doGetAttribute(1, LAST_ERROR_ATTRIBUTE, zoneId);
        return status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getSynchronizationSummary(final String zoneId)
    {
        final String status = (String) this.doGetAttribute(1, SUMMARY_ATTRIBUTE, zoneId);
        return status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onBootstrap(final ApplicationEvent event)
    {
        if (this.syncOnStartup)
        {
            // we only trigger the same sync for default tenant as default Alfresco does
            AuthenticationUtil.runAsSystem(() -> {
                try
                {
                    this.synchronize(false, false, true);
                }
                catch (final RuntimeException e)
                {
                    if (this.tenantService.isEnabled())
                    {
                        LOGGER.warn("Failed initial synchronize with user registries in -default- tenant", e);
                    }
                    else
                    {
                        LOGGER.warn("Failed initial synchronize with user registries", e);
                    }
                }

                return null;
            });
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onShutdown(final ApplicationEvent event)
    {
        // NO-OP
    }

    protected boolean checkPluginIsActive(final UserRegistry plugin)
    {
        final String currentDomain = TenantUtil.getCurrentDomain();
        boolean active;
        if (plugin instanceof TenantAwareUserRegistry)
        {
            active = ((TenantAwareUserRegistry) plugin).isActiveForTenant(currentDomain);
        }
        else if (TenantService.DEFAULT_DOMAIN.equals(currentDomain))
        {
            if (plugin instanceof ActivateableBean)
            {
                active = ((ActivateableBean) plugin).isActive();
            }
            else
            {
                active = false;
            }
        }
        else
        {
            active = false;
        }
        return active;
    }

    protected Date getMostRecentUpdateTime(final String label, final String zoneId, final boolean splitTxns)
    {
        final Long result = this.inReadOnlyTransaction(() -> {
            final Long updateTime = (Long) this.doGetAttribute(1, label, zoneId);
            return updateTime;
        }, splitTxns);

        final Date time = result != null ? new Date(result.longValue()) : null;
        return time;
    }

    protected void setMostRecentUpdateTime(final String label, final String zoneId, final long time, final boolean splitTxns)
    {
        this.inTransaction(() -> {
            this.doSetAttribute(Long.valueOf(time), 1, label, zoneId);
            return null;
        }, splitTxns);
    }

    protected <T> T inReadOnlyTransaction(final RetryingTransactionCallback<T> cb, final boolean splitTxn)
    {
        final T result = this.transactionService.getRetryingTransactionHelper().doInTransaction(cb, true, splitTxn);
        return result;
    }

    protected <T> T inTransaction(final RetryingTransactionCallback<T> cb, final boolean splitTxn)
    {
        final T result = this.transactionService.getRetryingTransactionHelper().doInTransaction(cb, false, splitTxn);
        return result;
    }

    protected void testSynchronize(final UserRegistry userRegistry, final SynchronizeDiagnosticImpl diagnostic)
    {
        // test simple name retrievals
        final Collection<String> groupNames = userRegistry.getGroupNames();
        diagnostic.setGroups(groupNames);
        final Collection<String> personNames = userRegistry.getPersonNames();
        diagnostic.setUsers(personNames);

        // test complex retrievals with attribute mapping
        Date groupLastSynced = diagnostic.getGroupLastSynced();
        if (groupLastSynced == null)
        {
            groupLastSynced = new Date();
        }
        userRegistry.getGroups(groupLastSynced);

        Date personLastSynced = diagnostic.getPersonLastSynced();
        if (personLastSynced == null)
        {
            personLastSynced = new Date();
        }
        userRegistry.getPersons(personLastSynced);
    }

    protected void synchronize(final boolean forceUpdate, final boolean isFullSync, final boolean splitTxns)
    {
        final String currentDomain = TenantUtil.getCurrentDomain();
        LOGGER.debug("Running {} sync with deletions {}allowed in tenant {}", forceUpdate ? "full" : "differential",
                this.allowDeletions ? "" : "not ", TenantService.DEFAULT_DOMAIN.equals(currentDomain) ? "-default-" : currentDomain);

        final QName lockQName = this.getLockQNameForCurrentTenant();
        String lockToken;
        try
        {
            // splitTxns = true: likely startup-triggered so give up immediately if lock not available
            // (startup of a cluster node already performs synchronisation)
            // splitTxns = false: likely login-triggered so wait for the lock and retry
            lockToken = this.jobLockService.getLock(lockQName, LOCK_TTL, splitTxns ? 0 : LOCK_TTL, splitTxns ? 1 : 10);
        }
        catch (final LockAcquisitionException laex)
        {
            LOGGER.warn("User registry synchronization already running in another thread / on another cluster node. Synchronize aborted");
            lockToken = null;
        }

        if (lockToken != null)
        {
            final AtomicBoolean synchRunning = new AtomicBoolean(true);
            final AtomicBoolean lockReleased = new AtomicBoolean(false);
            try
            {
                // original class used complex setup with asynch refresher thread
                // this was legacy never adapted when JobLockRefreshCallback was introduced in 3.4
                this.jobLockService.refreshLock(lockToken, lockQName, LOCK_TTL, new JobLockRefreshCallback()
                {

                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public void lockReleased()
                    {
                        lockReleased.set(true);
                    }

                    @Override
                    public boolean isActive()
                    {
                        return synchRunning.get();
                    }
                });

                final Map<String, UserRegistry> plugins = this.getPluginsToSync();
                final Set<String> visitedIds = new TreeSet<>();
                this.notifySyncStart(plugins.keySet());

                for (final Entry<String, UserRegistry> pluginEntry : plugins.entrySet())
                {
                    final String id = pluginEntry.getKey();
                    final UserRegistry plugin = pluginEntry.getValue();

                    if (LOGGER.isDebugEnabled() && this.mbeanServer != null)
                    {
                        this.logPluginConfig(id);
                    }

                    LOGGER.info("Synchronizing users and groups with user registry {} in tenant {}", id,
                            TenantService.DEFAULT_DOMAIN.equals(currentDomain) ? "-default-" : currentDomain);
                    if (isFullSync)
                    {
                        LOGGER.info(
                                "Full synchronisation with user registry {} in tenant {} - deletions enabled: {} (if true, some users and groups previously created by synchronization with this user registry may be removed, otherwise users / groups removed from this registry will be logged only and remain in the repository while users previously found in a different registry will be moved in the repository rather than recreated)",
                                id, TenantService.DEFAULT_DOMAIN.equals(currentDomain) ? "-default-" : currentDomain, this.allowDeletions);
                    }

                    final boolean requiresNew = splitTxns
                            || AlfrescoTransactionSupport.getTransactionReadState() == TxnReadState.TXN_READ_ONLY;

                    this.syncWithPlugin(id, plugin, forceUpdate, isFullSync, requiresNew, visitedIds, plugins.keySet());

                    this.applicationEventPublisher.publishEvent(new SynchronizeDirectoryEndEvent(this, id));
                }

                this.notifySyncEnd();
            }
            catch (final RuntimeException re)
            {
                this.notifySyncEnd(re);
                LOGGER.error("Synchronization aborted due to error", re);
                throw re;
            }
            finally
            {
                synchRunning.set(false);
                this.jobLockService.releaseLock(lockToken, lockQName);
            }
        }
    }

    protected void syncWithPlugin(final String id, final UserRegistry userRegistry, final boolean forceUpdate, final boolean isFullSync,
            final boolean splitTxns, final Set<String> visitedIds, final Set<String> allIds)
    {
        final String tenantDomain = TenantUtil.getCurrentDomain();
        final String batchId;
        final String technicalTenantIdentifier;
        if (TenantService.DEFAULT_DOMAIN.equals(tenantDomain))
        {
            batchId = id;
            technicalTenantIdentifier = TenantUtil.DEFAULT_TENANT;
        }
        else
        {
            batchId = this.tenantService.getName(id);
            technicalTenantIdentifier = tenantDomain;
        }

        final String reservedBatchProcessNames[] = { SyncProcess.GROUP_ANALYSIS.getTitle(batchId),
                SyncProcess.USER_UPDATE_AND_CREATION.getTitle(batchId),
                SyncProcess.GROUP_CREATION_AND_ASSOCIATION_DELETION.getTitle(batchId),
                SyncProcess.GROUP_ASSOCIATION_CREATION.getTitle(batchId), SyncProcess.USER_ASSOCIATION.getTitle(batchId),
                SyncProcess.AUTHORITY_DELETION.getTitle(batchId) };

        this.notifySyncDirectoryStart(id, reservedBatchProcessNames);
        try
        {
            final Date groupLastModified = this.getMostRecentUpdateTime(GROUP_LAST_MODIFIED_ATTRIBUTE, id, splitTxns);
            final Date personLastModified = this.getMostRecentUpdateTime(PERSON_LAST_MODIFIED_ATTRIBUTE, id, splitTxns);

            if (groupLastModified != null)
            {
                LOGGER.info(
                        "Retrieving groups changed since {} from user registry {} of tenant {}", DateFormat
                                .getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.getDefault()).format(groupLastModified),
                        id, technicalTenantIdentifier);
            }
            else
            {
                LOGGER.info("Retrieving all groups from user registry {} of tenant {}", id, technicalTenantIdentifier);
            }

            final BatchProcessor<NodeDescription> groupAnalysisProcessor = new BatchProcessor<>(
                    SyncProcess.GROUP_ANALYSIS.getTitle(batchId), this.transactionService.getRetryingTransactionHelper(),
                    new UserRegistryNodeCollectionWorkProvider(userRegistry.getGroups(groupLastModified)), this.workerThreads,
                    USER_REGISTRY_ENTITY_BATCH_SIZE, this.applicationEventPublisher,
                    LogFactory.getLog(TenantAwareChainingUserRegistrySynchronizer.class), this.loggingInterval);
            final Analyzer groupAnalyzer = this.createAnalyzer(id, visitedIds, allIds);
            int groupProcessedCount = groupAnalysisProcessor.process(groupAnalyzer, splitTxns);

            this.processGroupCreationAndAssociationDeletion(id, batchId, groupAnalyzer, splitTxns);
            this.processGroupAssociationCreation(batchId, groupAnalyzer, splitTxns);

            if (personLastModified != null)
            {
                LOGGER.info("Retrieving users changed since {} from user registry {} of tenant {}", DateFormat
                        .getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.getDefault()).format(personLastModified), id,
                        technicalTenantIdentifier);
            }
            else
            {
                LOGGER.info("Retrieving all users from user registry {} of tenant {}", id, technicalTenantIdentifier);
            }

            final BatchProcessor<NodeDescription> userProcessor = new BatchProcessor<>(
                    SyncProcess.USER_UPDATE_AND_CREATION.getTitle(batchId), this.transactionService.getRetryingTransactionHelper(),
                    new UserRegistryNodeCollectionWorkProvider(userRegistry.getPersons(groupLastModified)), this.workerThreads,
                    USER_REGISTRY_ENTITY_BATCH_SIZE, this.applicationEventPublisher,
                    LogFactory.getLog(TenantAwareChainingUserRegistrySynchronizer.class), this.loggingInterval);

            UserAccountInterpreter accountInterpreter;
            if (userRegistry instanceof EnhancedUserRegistry)
            {
                final String externalUserControl = this.externalUserControl.get(technicalTenantIdentifier);
                final String externalUserControlSubsystemName = this.externalUserControlSubsystemName.get(technicalTenantIdentifier);
                if (Boolean.parseBoolean(externalUserControl) && id.equals(externalUserControlSubsystemName))
                {
                    accountInterpreter = ((EnhancedUserRegistry) userRegistry).getUserAccountInterpreter();
                }
                else
                {
                    accountInterpreter = null;
                }
            }
            else
            {
                accountInterpreter = null;
            }

            final PersonWorker userWorker = this.createPersonWorker(id, visitedIds, allIds, accountInterpreter);
            int userProcessedCount = userProcessor.process(userWorker, splitTxns);

            this.processUserAssociation(batchId, groupAnalyzer, splitTxns);

            final long newLatestGroupModified = groupAnalyzer.getLatestModified();
            if (newLatestGroupModified > 0)
            {
                this.setMostRecentUpdateTime(GROUP_LAST_MODIFIED_ATTRIBUTE, id, newLatestGroupModified, splitTxns);
            }

            final long newLatestUserModified = userWorker.getLatestModified();
            if (newLatestUserModified > 0)
            {
                this.setMostRecentUpdateTime(PERSON_LAST_MODIFIED_ATTRIBUTE, id, newLatestUserModified, splitTxns);
            }

            final Pair<Integer, Integer> deletionCounts = this.processAuthorityDeletions(id, batchId, userRegistry, isFullSync, splitTxns);
            userProcessedCount += deletionCounts.getFirst().intValue();
            groupProcessedCount += deletionCounts.getSecond().intValue();

            visitedIds.add(id);

            final Object statusParams[] = { Integer.valueOf(userProcessedCount), Integer.valueOf(groupProcessedCount) };
            final String statusMessage = I18NUtil.getMessage("synchronization.summary.status", statusParams);

            LOGGER.info("Finished synchronizing users and groups with user registry {} of tenant {}", id, technicalTenantIdentifier);
            LOGGER.info(statusMessage);

            this.notifySyncDirectoryEnd(id, statusMessage);
        }
        catch (final RuntimeException e)
        {
            this.notifySyncDirectoryEnd(id, e);
            throw e;
        }
    }

    protected void processGroupCreationAndAssociationDeletion(final String id, final String batchId, final Analyzer groupAnalyzer,
            final boolean splitTxns)
    {
        final Map<String, String> groupsToCreate = groupAnalyzer.getGroupsToCreate();
        final Map<String, Set<String>> groupParentsToRemove = groupAnalyzer.getGroupParentsToRemove();

        final Collection<String> groupsToProcess = new HashSet<>(groupsToCreate.keySet());
        groupsToProcess.addAll(groupParentsToRemove.keySet());

        if (!groupsToProcess.isEmpty())
        {
            @SuppressWarnings("deprecation")
            final BatchProcessor<String> groupProcessor = new BatchProcessor<>(
                    SyncProcess.GROUP_CREATION_AND_ASSOCIATION_DELETION.getTitle(batchId),
                    this.transactionService.getRetryingTransactionHelper(), groupsToProcess, this.workerThreads,
                    USER_REGISTRY_ENTITY_BATCH_SIZE, this.applicationEventPublisher,
                    LogFactory.getLog(TenantAwareChainingUserRegistrySynchronizer.class), this.loggingInterval);

            final String zoneId = asZoneId(id);
            final Set<String> zones = new HashSet<>();
            zones.add(AuthorityService.ZONE_APP_DEFAULT);
            zones.add(zoneId);
            final GroupCreationAndParentRemovalWorker worker = new GroupCreationAndParentRemovalWorker(zones, groupsToCreate,
                    groupParentsToRemove, this.createComponentLookupCallback());
            groupProcessor.process(worker, splitTxns);
        }
    }

    protected void processGroupAssociationCreation(final String batchId, final Analyzer groupAnalyzer, final boolean splitTxns)
    {
        final Map<String, Set<String>> groupParentsToAdd = groupAnalyzer.getGroupParentsToAdd();

        final Collection<String> groupsToProcess = new HashSet<>(groupParentsToAdd.keySet());

        if (!groupsToProcess.isEmpty())
        {
            @SuppressWarnings("deprecation")
            final BatchProcessor<String> groupProcessor = new BatchProcessor<>(SyncProcess.GROUP_ASSOCIATION_CREATION.getTitle(batchId),
                    this.transactionService.getRetryingTransactionHelper(), groupsToProcess, this.workerThreads,
                    USER_REGISTRY_ENTITY_BATCH_SIZE, this.applicationEventPublisher,
                    LogFactory.getLog(TenantAwareChainingUserRegistrySynchronizer.class), this.loggingInterval);

            final GroupParentAdditionWorker worker = new GroupParentAdditionWorker(groupParentsToAdd, this.createComponentLookupCallback());
            groupProcessor.process(worker, splitTxns);
        }
    }

    protected void processUserAssociation(final String batchId, final Analyzer groupAnalyzer, final boolean splitTxns)
    {
        final Map<String, Set<String>> userParentsToAdd = groupAnalyzer.getUserParentsToAdd();
        final Map<String, Set<String>> userParentsToRemove = groupAnalyzer.getUserParentsToRemove();

        final Collection<String> usersToProcess = new HashSet<>(userParentsToRemove.keySet());
        usersToProcess.addAll(userParentsToAdd.keySet());

        if (!usersToProcess.isEmpty())
        {
            @SuppressWarnings("deprecation")
            final BatchProcessor<String> userProcessor = new BatchProcessor<>(SyncProcess.USER_ASSOCIATION.getTitle(batchId),
                    this.transactionService.getRetryingTransactionHelper(), usersToProcess, this.workerThreads,
                    USER_REGISTRY_ENTITY_BATCH_SIZE, this.applicationEventPublisher,
                    LogFactory.getLog(TenantAwareChainingUserRegistrySynchronizer.class), this.loggingInterval);

            final UserParentWorker worker = new UserParentWorker(userParentsToAdd, userParentsToRemove,
                    this.createComponentLookupCallback());
            userProcessor.process(worker, splitTxns);
        }
    }

    protected Pair<Integer, Integer> processAuthorityDeletions(final String id, final String batchId, final UserRegistry userRegistry,
            final boolean isFullSync, final boolean splitTxns)
    {
        final String zoneId = asZoneId(id);
        final Set<String> groupsToDelete = new HashSet<>();
        final Set<String> usersToDelete = new HashSet<>();

        final Pair<Integer, Integer> counts = new Pair<>(Integer.valueOf(0), Integer.valueOf(0));
        if (isFullSync)
        {
            final Set<String> allZoneGroups = new TreeSet<>();
            final Set<String> allZoneUsers = this.personService.getUserNamesAreCaseSensitive() ? new TreeSet<>()
                    : new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

            this.inReadOnlyTransaction(() -> {
                allZoneGroups.addAll(this.authorityService.getAllAuthoritiesInZone(zoneId, AuthorityType.GROUP));
                allZoneUsers.addAll(this.authorityService.getAllAuthoritiesInZone(zoneId, AuthorityType.USER));
                return null;
            }, splitTxns);

            groupsToDelete.addAll(allZoneGroups);
            groupsToDelete.removeAll(userRegistry.getGroupNames());

            usersToDelete.addAll(allZoneUsers);
            final String currentDomain = TenantUtil.getCurrentDomain();
            for (final String userName : userRegistry.getPersonNames())
            {
                final String domainUser;
                final String primaryDomain = this.tenantService.getPrimaryDomain(userName);
                if (!EqualsHelper.nullSafeEquals(primaryDomain, currentDomain))
                {
                    domainUser = this.tenantService.getDomainUser(userName, currentDomain);
                }
                else
                {
                    domainUser = userName;
                }
                usersToDelete.remove(domainUser);
            }

            final Set<String> authoritiesToDelete = new TreeSet<>();
            authoritiesToDelete.addAll(groupsToDelete);
            authoritiesToDelete.addAll(usersToDelete);

            if (!authoritiesToDelete.isEmpty() && (this.allowDeletions || this.syncDelete))
            {
                @SuppressWarnings("deprecation")
                final BatchProcessor<String> deletionProcessor = new BatchProcessor<>(SyncProcess.AUTHORITY_DELETION.getTitle(batchId),
                        this.transactionService.getRetryingTransactionHelper(), authoritiesToDelete, this.workerThreads,
                        USER_REGISTRY_ENTITY_BATCH_SIZE, this.applicationEventPublisher,
                        LogFactory.getLog(TenantAwareChainingUserRegistrySynchronizer.class), this.loggingInterval);

                final AuthorityDeleter deleter = new AuthorityDeleter(zoneId, groupsToDelete, usersToDelete, this.allowDeletions,
                        this.createComponentLookupCallback());
                deletionProcessor.process(deleter, splitTxns);

                counts.setFirst(Integer.valueOf(usersToDelete.size()));
                counts.setSecond(Integer.valueOf(groupsToDelete.size()));
            }
        }
        return counts;
    }

    /**
     * Retrieves the user registry plugins that need to be processed in the synchronisation for the current tenant.
     *
     * @return the map of plugins to their instance ID with guaranteed ordering with regards to configured subsystem priorities
     */
    protected Map<String, UserRegistry> getPluginsToSync()
    {
        final Map<String, UserRegistry> plugins = new LinkedHashMap<>();

        final Collection<String> instanceIds = this.applicationContextManager.getInstanceIds();
        for (final String id : instanceIds)
        {
            final ApplicationContext context = this.applicationContextManager.getApplicationContext(id);
            final UserRegistry plugin;

            try
            {
                plugin = (UserRegistry) context.getBean(this.userRegistrySourceBeanName);
            }
            catch (final RuntimeException re)
            {
                if (LOGGER.isTraceEnabled())
                {
                    LOGGER.trace("Subsystem {} cannot be used in synchronisation", id, re);
                }
                else
                {
                    LOGGER.debug("Subsystem {} cannot be used in synchronisation", id);
                }
                continue;
            }

            final boolean active = this.checkPluginIsActive(plugin);

            if (active)
            {
                plugins.put(id, plugin);
            }
        }

        return plugins;
    }

    protected QName getLockQNameForCurrentTenant()
    {
        final String tenantDomain = TenantUtil.getCurrentDomain();
        QName lockQName;

        if (TenantService.DEFAULT_DOMAIN.equals(tenantDomain))
        {
            lockQName = DEFAULT_LOCK_QNAME;
        }
        else
        {
            lockQName = QName.createQName(NamespaceService.SYSTEM_MODEL_1_0_URI,
                    "TenantAwareChainingUserRegistrySynchronizer@" + tenantDomain);
        }

        return lockQName;
    }

    protected void logPluginConfig(final String id)
    {
        try
        {
            final StringBuilder nameBuff = new StringBuilder(200)
                    .append("Alfresco:Type=Configuration,Category=Authentication,id1=managed,id2=").append(URLDecoder.decode(id, "UTF-8"));
            final ObjectName name = new ObjectName(nameBuff.toString());
            if (this.mbeanServer != null && this.mbeanServer.isRegistered(name))
            {
                final MBeanInfo info = this.mbeanServer.getMBeanInfo(name);
                final MBeanAttributeInfo[] attributes = info.getAttributes();
                LOGGER.debug("{} attributes:", id);
                for (final MBeanAttributeInfo attribute : attributes)
                {
                    final Object value = this.mbeanServer.getAttribute(name, attribute.getName());
                    LOGGER.debug("{} = {}", attribute.getName(), value);
                }
            }
        }
        catch (final MalformedObjectNameException | InstanceNotFoundException | IntrospectionException | AttributeNotFoundException
                | ReflectionException | MBeanException | IOException e)
        {
            LOGGER.warn("Exception during logging", e);
        }
    }

    protected void notifySyncStart(final Set<String> toSync)
    {
        final String serverId = this.sysAdminParams.getAlfrescoHost() + ":" + this.sysAdminParams.getAlfrescoPort();
        this.applicationEventPublisher.publishEvent(new SynchronizeStartEvent(this));
        this.inTransaction(() -> {

            this.doSetAttribute(new Date().getTime(), true, START_TIME_ATTRIBUTE);
            this.doSetAttribute(-1L, true, END_TIME_ATTRIBUTE);
            this.doSetAttribute(serverId, true, SERVER_ATTRIBUTE);
            this.doSetAttribute(SyncStatus.IN_PROGRESS.toString(), true, STATUS_ATTRIBUTE);

            this.doRemoveAttributes(0, LAST_ERROR_ATTRIBUTE);
            this.doRemoveAttributes(0, SUMMARY_ATTRIBUTE);
            this.doRemoveAttributes(0, STATUS_ATTRIBUTE);

            for (final String zoneId : toSync)
            {
                this.doSetAttribute(SyncStatus.WAITING.toString(), 0, STATUS_ATTRIBUTE, zoneId);
            }

            return null;
        }, true);
    }

    protected void notifySyncEnd()
    {
        this.applicationEventPublisher.publishEvent(new SynchronizeEndEvent(this));
        this.inTransaction(() -> {

            this.doSetAttribute(SyncStatus.COMPLETE.toString(), true, STATUS_ATTRIBUTE);
            this.doSetAttribute(new Date().getTime(), true, END_TIME_ATTRIBUTE);

            return null;
        }, true);
    }

    protected void notifySyncEnd(final Exception e)
    {
        this.applicationEventPublisher.publishEvent(new SynchronizeEndEvent(this, e));
        this.inTransaction(() -> {

            this.doSetAttribute(e.getMessage(), false, LAST_ERROR_ATTRIBUTE);
            this.doSetAttribute(SyncStatus.COMPLETE_ERROR.toString(), false, STATUS_ATTRIBUTE);
            this.doSetAttribute(new Date().getTime(), false, END_TIME_ATTRIBUTE);

            return null;
        }, true);
    }

    protected void notifySyncDirectoryStart(final String zoneId, final String[] batchProcessNames)
    {
        this.applicationEventPublisher.publishEvent(new SynchronizeDirectoryStartEvent(this, zoneId, batchProcessNames));
        this.inTransaction(() -> {

            this.doSetAttribute(SyncStatus.IN_PROGRESS.toString(), 0, STATUS_ATTRIBUTE, zoneId);
            this.doRemoveAttributes(0, SUMMARY_ATTRIBUTE, zoneId);

            return null;
        }, true);
    }

    protected void notifySyncDirectoryEnd(final String zoneId, final String statusMessage)
    {
        this.applicationEventPublisher.publishEvent(new SynchronizeDirectoryEndEvent(this, zoneId));
        this.inTransaction(() -> {

            this.doSetAttribute(SyncStatus.COMPLETE.toString(), 0, STATUS_ATTRIBUTE, zoneId);
            this.doRemoveAttributes(0, LAST_ERROR_ATTRIBUTE, zoneId);
            this.doSetAttribute(statusMessage, 0, SUMMARY_ATTRIBUTE, zoneId);

            return null;
        }, true);

    }

    protected void notifySyncDirectoryEnd(final String zoneId, final Exception e)
    {
        this.applicationEventPublisher.publishEvent(new SynchronizeDirectoryEndEvent(this, zoneId, e));
        this.inTransaction(() -> {

            this.doSetAttribute(SyncStatus.COMPLETE_ERROR.toString(), 0, STATUS_ATTRIBUTE, zoneId);
            this.doSetAttribute(e.getMessage(), 0, LAST_ERROR_ATTRIBUTE, zoneId);

            return null;
        }, true);
    }

    protected void doSetAttribute(final Serializable value, final int tenantScopeKeyIdx, final Serializable... keys)
    {
        final String tenantDomain = TenantUtil.getCurrentDomain();
        final Serializable[] realKeys = new Serializable[keys.length + 1];
        realKeys[0] = TenantService.DEFAULT_DOMAIN.equals(tenantDomain) ? ROOT_ATTRIBUTE_PATH : ROOT_MT_ATTRIBUTE_PATH;
        System.arraycopy(keys, 0, realKeys, 1, keys.length);

        if (!TenantService.DEFAULT_DOMAIN.equals(tenantDomain) && tenantScopeKeyIdx >= 0 && tenantScopeKeyIdx < keys.length)
        {
            if (keys[tenantScopeKeyIdx] instanceof String)
            {
                realKeys[tenantScopeKeyIdx + 1] = this.tenantService.getName((String) keys[tenantScopeKeyIdx]);
            }
        }

        this.attributeService.setAttribute(value, realKeys);
    }

    protected void doSetAttribute(final Serializable value, final boolean insertTenantBeforeKeys, final Serializable... keys)
    {
        final String tenantDomain = TenantUtil.getCurrentDomain();
        final Serializable[] realKeys;

        if (!TenantService.DEFAULT_DOMAIN.equals(tenantDomain))
        {
            realKeys = new Serializable[keys.length + 2];
            realKeys[insertTenantBeforeKeys ? 1 : (realKeys.length - 1)] = tenantDomain;
            System.arraycopy(keys, 0, realKeys, insertTenantBeforeKeys ? 2 : 1, keys.length);
        }
        else
        {
            realKeys = new Serializable[keys.length + 1];
            System.arraycopy(keys, 0, realKeys, 1, keys.length);
        }
        realKeys[0] = TenantService.DEFAULT_DOMAIN.equals(tenantDomain) ? ROOT_ATTRIBUTE_PATH : ROOT_MT_ATTRIBUTE_PATH;

        this.attributeService.setAttribute(value, realKeys);
    }

    protected Serializable doGetAttribute(final int tenantScopeKeyIdx, final Serializable... keys)
    {
        final String tenantDomain = TenantUtil.getCurrentDomain();
        final Serializable[] realKeys = new Serializable[keys.length + 1];
        realKeys[0] = TenantService.DEFAULT_DOMAIN.equals(tenantDomain) ? ROOT_ATTRIBUTE_PATH : ROOT_MT_ATTRIBUTE_PATH;
        System.arraycopy(keys, 0, realKeys, 1, keys.length);

        if (!TenantService.DEFAULT_DOMAIN.equals(tenantDomain) && tenantScopeKeyIdx >= 0 && tenantScopeKeyIdx < keys.length)
        {
            if (keys[tenantScopeKeyIdx] instanceof String)
            {
                realKeys[tenantScopeKeyIdx + 1] = this.tenantService.getName((String) keys[tenantScopeKeyIdx]);
            }
        }

        final Serializable value = this.attributeService.getAttribute(realKeys);
        return value;
    }

    protected Serializable doGetAttribute(final boolean insertTenantBeforeKeys, final Serializable... keys)
    {
        final String tenantDomain = TenantUtil.getCurrentDomain();
        final Serializable[] realKeys;

        if (!TenantService.DEFAULT_DOMAIN.equals(tenantDomain))
        {
            realKeys = new Serializable[keys.length + 2];
            realKeys[insertTenantBeforeKeys ? 1 : (realKeys.length - 1)] = tenantDomain;
            System.arraycopy(keys, 0, realKeys, insertTenantBeforeKeys ? 2 : 1, keys.length);
        }
        else
        {
            realKeys = new Serializable[keys.length + 1];
            System.arraycopy(keys, 0, realKeys, 1, keys.length);
        }
        realKeys[0] = TenantService.DEFAULT_DOMAIN.equals(tenantDomain) ? ROOT_ATTRIBUTE_PATH : ROOT_MT_ATTRIBUTE_PATH;

        final Serializable value = this.attributeService.getAttribute(realKeys);
        return value;
    }

    protected void doRemoveAttributes(final int tenantScopeKeyIdx, final Serializable... keys)
    {
        final String tenantDomain = TenantUtil.getCurrentDomain();
        final Serializable[] realKeys = new Serializable[keys.length + 1];
        realKeys[0] = TenantService.DEFAULT_DOMAIN.equals(tenantDomain) ? ROOT_ATTRIBUTE_PATH : ROOT_MT_ATTRIBUTE_PATH;
        System.arraycopy(keys, 0, realKeys, 1, keys.length);

        if (!TenantService.DEFAULT_DOMAIN.equals(tenantDomain) && tenantScopeKeyIdx >= 0 && tenantScopeKeyIdx < keys.length)
        {
            if (keys[tenantScopeKeyIdx] instanceof String)
            {
                realKeys[tenantScopeKeyIdx + 1] = this.tenantService.getName((String) keys[tenantScopeKeyIdx]);
            }
        }

        this.attributeService.removeAttributes(realKeys);
    }

    protected static String asZoneId(final String id)
    {
        return AuthorityService.ZONE_AUTH_EXT_PREFIX + id;
    }

    // most logic for actual synchronisation is externalised - these methods may be overriden to provide alternative components

    protected Analyzer createAnalyzer(final String id, final Collection<String> visitedIds, final Collection<String> allIds)
    {
        final String zoneId = asZoneId(id);
        final Set<String> zones = new HashSet<>();
        zones.add(AuthorityService.ZONE_APP_DEFAULT);
        zones.add(zoneId);

        final Analyzer groupAnalyzer = new AnalyzerImpl(id, zoneId, zones, visitedIds, allIds, this.allowDeletions,
                this.createComponentLookupCallback());
        return groupAnalyzer;
    }

    protected PersonWorker createPersonWorker(final String id, final Collection<String> visitedIds, final Collection<String> allIds,
            final UserAccountInterpreter accountInterpreter)
    {
        final String zoneId = asZoneId(id);
        final Set<String> zones = new HashSet<>();
        zones.add(AuthorityService.ZONE_APP_DEFAULT);
        zones.add(zoneId);

        final PersonWorker personWorker = new PersonWorkerImpl(id, zoneId, zones, visitedIds, allIds, this.allowDeletions,
                accountInterpreter, this.createComponentLookupCallback());
        return personWorker;
    }

    protected ComponentLookupCallback createComponentLookupCallback()
    {
        final ComponentLookupCallback callback = x -> {
            Object result;
            switch (x)
            {
                case "authorityService":
                    result = this.authorityService;
                    break;
                case "personService":
                    result = this.personService;
                    break;
                case "nameChecker":
                    result = this.nameChecker;
                    break;
                case "tenantService":
                    result = this.tenantService;
                    break;
                case "nodeService":
                    result = this.nodeService;
                    break;
                case "contentService":
                    result = this.contentService;
                    break;
                default:
                    throw new IllegalStateException(x + " is not available");
            }
            return result;
        };
        return callback;
    }
}
