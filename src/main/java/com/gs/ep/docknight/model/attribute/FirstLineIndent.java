/*
 *   Copyright 2020 Goldman Sachs.
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 */

package com.gs.ep.docknight.model.attribute;

import com.gs.ep.docknight.model.Element;
import com.gs.ep.docknight.model.Length;
import com.gs.ep.docknight.model.LengthAttribute;

/**
 * Attribute defined for first line indent of the element (first line indented,
 * subsequent lines flush left)
 */
public class FirstLineIndent extends LengthAttribute {

    private static final long serialVersionUID = 1L;

    public FirstLineIndent(Length value) {
        this.setValue(value);
    }

    @Override
    public Class getHolderInterface() {
        return Holder.class;
    }

    public interface Holder<E extends Element> {

        default E add(FirstLineIndent attribute) {
            E element = (E) this;
            element.addAttribute(attribute);
            return element;
        }

        default FirstLineIndent getFirstLineIndent() {
            E element = (E) this;
            return element.getAttribute(FirstLineIndent.class);
        }
    }
}
