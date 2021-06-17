/*
 * Copyright (c) 2021 by Andrew Binstock.
 *
 * Portions of this file are copyright Oracle Corp.
 * Those portions are licensed under GPL v. 2.0
 * with the Oracle classpath exception. Due to the
 * requirements of that license, the portions that
 * are copyrighted by Andrew Binstock are licensed
 * using the same terms and requirements.
 *
 */

package org.jacobin.jadis.exceptions;

/**
 * javap uses its own version of InternalError, rather than
 * the java.lang.InternalError in the standard library
 */
public class InternalError extends Error {
    private static final long serialVersionUID = 8114054446416187030L;
    public InternalError(Throwable t, Object... args) {
        super("Internal error", t);
        this.args = args;
    }

    public InternalError(Object... args) {
        super("Internal error");
        this.args = args;
    }

    public final Object[] args;
}
