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

/**
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public enum SyncProcess
{
    GROUP_ANALYSIS("1 Group Analysis"), GROUP_CREATION_AND_ASSOCIATION_DELETION(
            "2 Group Creation and Association Deletion"), GROUP_ASSOCIATION_CREATION(
                    "3 Group Association Creation"), USER_UPDATE_AND_CREATION("4 User Update and Creation"), USER_ASSOCIATION(
                            "5 User Association"), AUTHORITY_DELETION("6 Authority Deletion");

    SyncProcess(final String title)
    {
        this.title = title;
    }

    public String getTitle(final String zone)
    {
        return "Synchronization,Category=directory,id1=" + zone + ",id2=" + this.title;
    }

    private String title;
}
