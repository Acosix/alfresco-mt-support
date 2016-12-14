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
import java.util.Locale;

import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.tenant.TenantAdminService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.security.AuthenticationService;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.cmr.security.PersonService.PersonInfo;
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
public class AuthenticationTest
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
    public void loginNonExistingUserPMaierDefaultTenant()
    {
        final WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        final AuthenticationService authenticationService = context.getBean("AuthenticationService", AuthenticationService.class);

        this.thrown.expect(AuthenticationException.class);
        this.thrown.expectMessage("Failed to authenticate");
        authenticationService.authenticate("pmaier", "pmaier".toCharArray());
    }

    @Test
    public void loginExistingUserAFaustDefaultTenant()
    {
        final WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        final AuthenticationService authenticationService = context.getBean("AuthenticationService", AuthenticationService.class);

        authenticationService.authenticate("afaust", "afaust".toCharArray());
    }

    @Test
    public void checkUserEagerSynchAndLoginMMustermannDefaultTenant()
    {
        final WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        final PersonService personService = context.getBean("PersonService", PersonService.class);

        AuthenticationUtil.setFullyAuthenticatedUser("admin");
        Assert.assertTrue("User mmustermann should have been eagerly synchronized", personService.personExists("mmustermann"));

        final NodeRef personNodeRef = personService.getPerson("mmustermann");
        final PersonInfo personInfo = personService.getPerson(personNodeRef);

        Assert.assertEquals("First name of mustermann should have been synchronized", "Max", personInfo.getFirstName());
        Assert.assertEquals("Last name of mmustermann should have been synchronized", "Mustermann", personInfo.getLastName());

        final AuthenticationService authenticationService = context.getBean("AuthenticationService", AuthenticationService.class);

        authenticationService.authenticate("mmustermann", "mmustermann".toCharArray());
    }

    @Test
    public void loginExistingUserAFaustTenantAlphaAndEagerSynch()
    {
        final WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        final TenantAdminService tenantAdminService = context.getBean("TenantAdminService", TenantAdminService.class);

        AuthenticationUtil.setFullyAuthenticatedUser("admin");
        if (!tenantAdminService.existsTenant("tenantalpha"))
        {
            tenantAdminService.createTenant("tenantalpha", "admin".toCharArray());
        }
        AuthenticationUtil.clearCurrentSecurityContext();

        final AuthenticationService authenticationService = context.getBean("AuthenticationService", AuthenticationService.class);

        authenticationService.authenticate("afaust@tenantalpha", "afaust".toCharArray());

        final PersonService personService = context.getBean("PersonService", PersonService.class);
        Assert.assertTrue("User afaust@tenantalpha should have been lazily created", personService.personExists("afaust@tenantalpha"));

        NodeRef personNodeRef = personService.getPerson("afaust@tenantalpha");
        PersonInfo personInfo = personService.getPerson(personNodeRef);

        Assert.assertEquals("First name of afaust@tenantalpha should have been synchronized", "Axel", personInfo.getFirstName());
        Assert.assertEquals("Last name of afaust@tenantalpha should have been synchronized", "Faust", personInfo.getLastName());

        Assert.assertTrue("User mmustermann@tenantalpha should not have been eagerly synchronized",
                personService.personExists("mmustermann@tenantalpha"));

        personNodeRef = personService.getPerson("mmustermann@tenantalpha");
        personInfo = personService.getPerson(personNodeRef);

        Assert.assertEquals("First name of mustermann should have been synchronized", "Max", personInfo.getFirstName());
        Assert.assertEquals("Last name of mmustermann should have been synchronized", "Mustermann", personInfo.getLastName());
    }

    @Test
    public void loginExistingUserAFaustTenantBetaAndNoSynch()
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

        final PersonService personService = context.getBean("PersonService", PersonService.class);
        Assert.assertTrue("User afaust should have been lazily created", personService.personExists("afaust@tenantbeta"));

        final NodeRef personNodeRef = personService.getPerson("afaust@tenantbeta");
        final PersonInfo personInfo = personService.getPerson(personNodeRef);

        Assert.assertEquals("First name of afaust@tenantbeta should not have been synchronized", "afaust", personInfo.getFirstName());
        Assert.assertEquals("Last name of afaust@tenantbeta should not have been synchronized", "", personInfo.getLastName());

        Assert.assertFalse("User mmustermann@tenantbeta should not have been eagerly synchronized",
                personService.personExists("mmustermann@tenantbeta"));
    }
}
