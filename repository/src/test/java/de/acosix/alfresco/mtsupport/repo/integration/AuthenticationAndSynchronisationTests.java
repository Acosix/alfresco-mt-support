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
package de.acosix.alfresco.mtsupport.repo.integration;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.tenant.TenantAdminService;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.cmr.security.PersonService.PersonInfo;
import org.alfresco.service.namespace.RegexQNamePattern;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.api.maven.PomEquippedResolveStage;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.springframework.extensions.surf.util.I18NUtil;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.WebApplicationContext;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
@RunWith(Arquillian.class)
public class AuthenticationAndSynchronisationTests
{

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Deployment
    public static WebArchive create()
    {
        final PomEquippedResolveStage configureResolverViaPlugin = Maven.configureResolverViaPlugin();
        final File warFile = configureResolverViaPlugin.resolve("org.alfresco:alfresco:war:?").withoutTransitivity().asSingleFile();
        final File[] libraries = configureResolverViaPlugin
                .resolve(Arrays.asList("org.alfresco:alfresco-repository:jar:h2scripts:?", "com.h2database:h2:jar:?",
                        "de.acosix.alfresco.utility:de.acosix.alfresco.utility.common:jar:?",
                        "de.acosix.alfresco.utility:de.acosix.alfresco.utility.repo:jar:installable:?"))
                .withoutTransitivity().asFile();
        final WebArchive archive = ShrinkWrap.createFromZipFile(WebArchive.class, warFile);
        archive.addAsLibraries(libraries);

        archive.addAsLibrary("installable-de.acosix.alfresco.mtsupport.repo.jar");

        archive.addAsResource("configRoot/alfresco-global.properties", "alfresco-global.properties");
        archive.addAsResource("configRoot/log4j.properties", "log4j.properties");
        archive.addAsResource("configRoot/alfresco/extension/dev-log4j.properties", "alfresco/extension/dev-log4j.properties");
        archive.addAsResource("configRoot/alfresco/extension/subsystems/Authentication/mt-ldap/test/custom.properties",
                "alfresco/extension/subsystems/Authentication/mt-ldap/test/custom.properties");
        return archive;
    }

    @Before
    public void before()
    {
        AuthenticationUtil.clearCurrentSecurityContext();
        // ensure we have a defined locale for exception message checking
        I18NUtil.setLocale(Locale.ENGLISH);
    }

    @Test
    public void loginNonExistingUserDefaultTenant()
    {
        final WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        final AuthenticationService authenticationService = context.getBean("AuthenticationService", AuthenticationService.class);

        this.thrown.expect(AuthenticationException.class);
        this.thrown.expectMessage("Failed to authenticate");
        authenticationService.authenticate("pmaier", "pmaier".toCharArray());
    }

    @Test
    public void loginExistingUsersDefaultTenant()
    {
        final WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        final AuthenticationService authenticationService = context.getBean("AuthenticationService", AuthenticationService.class);

        authenticationService.authenticate("afaust", "afaust".toCharArray());
        authenticationService.authenticate("mmustermann", "mmustermann".toCharArray());
    }

    @Test
    public void checkUserEagerSynchDefaultTenant()
    {
        AuthenticationUtil.setFullyAuthenticatedUser("admin");
        this.checkUserExistsAndState("afaust", "Axel", "Faust", true);
        this.checkUserExistsAndState("mmustermann", "Max", "Mustermann", false);
    }

    protected void checkUserExistsAndState(final String userName, final String firstName, final String lastName,
            final boolean avatarMustExist)
    {
        final WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        final PersonService personService = context.getBean("PersonService", PersonService.class);

        Assert.assertTrue("User mmustermann should have been eagerly synchronized", personService.personExists(userName));
        final NodeRef personNodeRef = personService.getPerson(userName, false);
        final PersonInfo personInfo = personService.getPerson(personNodeRef);

        Assert.assertEquals("First name of user should have been synchronized", firstName, personInfo.getFirstName());
        Assert.assertEquals("Last name of user should have been synchronized", lastName, personInfo.getLastName());

        final NodeService nodeService = context.getBean("NodeService", NodeService.class);
        final ContentService contentService = context.getBean("ContentService", ContentService.class);

        final List<ChildAssociationRef> avatarAssocs = nodeService.getChildAssocs(personNodeRef, ContentModel.ASSOC_PREFERENCE_IMAGE,
                RegexQNamePattern.MATCH_ALL);
        Assert.assertEquals("No user thumbnail has been synchronized", avatarMustExist ? 1 : 0, avatarAssocs.size());
        if (avatarMustExist)
        {
            final NodeRef avatar = avatarAssocs.get(0).getChildRef();
            final ContentReader reader = contentService.getReader(avatar, ContentModel.PROP_CONTENT);

            Assert.assertNotNull("Avatar should have content", reader);
            Assert.assertTrue("Avatar should exist", reader.exists());
            Assert.assertNotEquals("Avatar should not be zero-byte", 0, reader.getSize());
            Assert.assertEquals("Avatar mimetype should have been image/jpeg", MimetypeMap.MIMETYPE_IMAGE_JPEG, reader.getMimetype());
        }
    }

    @Test
    public void loginOnDemandSynchTenantAlpha()
    {
        final WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        final TenantAdminService tenantAdminService = context.getBean("TenantAdminService", TenantAdminService.class);

        AuthenticationUtil.setFullyAuthenticatedUser("admin");
        if (!tenantAdminService.existsTenant("tenantalpha"))
        {
            tenantAdminService.createTenant("tenantalpha", "admin".toCharArray());
        }
        final PersonService personService = context.getBean("PersonService", PersonService.class);
        Assert.assertFalse("User afaust@tenantalpha should not have been synchronized yet",
                personService.personExists("afaust@tenantalpha"));
        AuthenticationUtil.clearCurrentSecurityContext();

        final AuthenticationService authenticationService = context.getBean("AuthenticationService", AuthenticationService.class);
        authenticationService.authenticate("afaust@tenantalpha", "afaust".toCharArray());
        this.checkUserExistsAndState("afaust@tenantalpha", "Axel", "Faust", false);
    }

    @Test
    public void loginNoSyncTenantBeta()
    {
        final WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        final TenantAdminService tenantAdminService = context.getBean("TenantAdminService", TenantAdminService.class);

        AuthenticationUtil.setFullyAuthenticatedUser("admin");
        if (!tenantAdminService.existsTenant("tenantbeta"))
        {
            tenantAdminService.createTenant("tenantbeta", "admin".toCharArray());
        }
        AuthenticationUtil.clearCurrentSecurityContext();

        final AuthenticationService authenticationService = context.getBean("AuthenticationService", AuthenticationService.class);

        authenticationService.authenticate("afaust@tenantbeta", "afaust".toCharArray());
        this.checkUserExistsAndState("afaust@tenantbeta", "afaust", "", false);

        final PersonService personService = context.getBean("PersonService", PersonService.class);
        Assert.assertFalse("User mmustermann@tenantbeta should not have been eagerly synchronized",
                personService.personExists("mmustermann@tenantbeta"));
    }
}
