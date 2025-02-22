// Copyright 2023 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.pure.runtime.java.compiled.serialization.binary;

import org.eclipse.collections.api.RichIterable;

public abstract class StringCacheOrIndex
{
    public abstract RichIterable<String> getClassifierIds();

    public static int classifierIdStringIndexToId(int index)
    {
        return -index - 1;
    }

    public static int classifierIdStringIdToIndex(int id)
    {
        return -(id + 1);
    }

    public static int otherStringIndexToId(int index)
    {
        return index + 1;
    }

    public static int otherStringIdToIndex(int id)
    {
        return id - 1;
    }
}
