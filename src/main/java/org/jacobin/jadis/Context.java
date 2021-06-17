/*
 * Copyright (c) 2021 by Andrew Binstock.
 *
 * Portions of this file are copyright Oracle Corp.
 * Those portions are licensed under GPL v. 2.0
 * with the Oracle classpath exception. Due to the
 * requirements of that license, the portions that
 * are copyrighted by Andrew Binstock are licensed
 * using the same terms and requirements.
 */

package org.jacobin.jadis;

import java.util.HashMap;
import java.util.Map;

/*
 * This is effectively a map containing global variables.
 */
public class Context {

    Map<Class<?>, Object> map;

    public Context() {
       map = new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> key) {
        return (T) map.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T put(Class<T> key, T value) {
        return (T) map.put(key, value);
    }
}
