/*
 * Copyright 2018-present HiveMQ and the HiveMQ Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hivemq.mqtt.client2.internal.collections;

import org.jetbrains.annotations.NotNull;

/**
 * @author Silvio Giebl
 */
public class HandleList<E> extends NodeList<HandleList.Handle<E>> {

    public static class Handle<E> extends NodeList.Node<Handle<E>> {

        private final @NotNull E element;

        Handle(final @NotNull E element) {
            this.element = element;
        }

        public @NotNull E getElement() {
            return element;
        }
    }

    public @NotNull Handle<E> add(final @NotNull E element) {
        final Handle<E> handle = new Handle<>(element);
        add(handle);
        return handle;
    }
}
