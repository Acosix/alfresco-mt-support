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

import java.io.Serializable;

/**
 * Instances of this class act as a simple wrapper for handling the binary data of a LDAP avatar image.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class AvatarBlobWrapper implements Serializable
{

    private static final long serialVersionUID = 1L;

    private final byte[] data;

    public AvatarBlobWrapper(final byte[] data)
    {
        this.data = new byte[data.length];
        System.arraycopy(data, 0, this.data, 0, data.length);
    }

    /**
     * @return the data
     */
    public byte[] getData()
    {
        final byte[] data = new byte[this.data.length];
        System.arraycopy(this.data, 0, data, 0, this.data.length);
        return data;
    }

}
