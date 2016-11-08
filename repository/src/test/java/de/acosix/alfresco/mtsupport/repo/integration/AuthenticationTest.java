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

import org.alfresco.service.cmr.security.AuthenticationService;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.api.maven.PomEquippedResolveStage;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.web.context.ContextLoader;
import org.springframework.web.context.WebApplicationContext;

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
@RunWith(Arquillian.class)
public class AuthenticationTest
{

    @Deployment
    public static WebArchive create()
    {
        final PomEquippedResolveStage configureResolverViaPlugin = Maven.configureResolverViaPlugin();
        final File warFile = configureResolverViaPlugin.resolve("org.alfresco:alfresco:war:?").withoutTransitivity().asSingleFile();
        final File[] libraries = configureResolverViaPlugin
                .resolve(Arrays.asList("org.alfresco:alfresco-repository:jar:h2scripts:?", "com.h2database:h2:jar:1.4.190",
                        "de.acosix.alfresco.utility:de.acosix.alfresco.utility.common:jar:?",
                        "de.acosix.alfresco.utility:de.acosix.alfresco.utility.repo:jar:installable:?"))
                .withoutTransitivity().asFile();
        final WebArchive archive = ShrinkWrap.createFromZipFile(WebArchive.class, warFile);
        archive.addAsLibraries(libraries);

        archive.addAsLibrary("installable-de.acosix.alfresco.mtsupport.repo.jar");

        archive.addAsResource("configRoot/alfresco-global.properties", "alfresco-global.properties");
        archive.addAsResource("configRoot/alfresco/extension/subsystems/Authentication/mt-ldap/test/custom.properties",
                "alfresco/extension/subsystems/Authentication/mt-ldap/test/custom.properties");
        return archive;
    }

    @Test
    public void loginNonExistingUser()
    {
        final WebApplicationContext context = ContextLoader.getCurrentWebApplicationContext();
        final AuthenticationService authenticationService = context.getBean("AuthenticationService", AuthenticationService.class);
        Assert.fail("Not yet implemented");
    }
}
