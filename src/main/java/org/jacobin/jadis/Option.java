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

package org.jacobin.jadis;

/*
 * Class for each individual option that javap allows
 * Extracted from JavapTask.java and lightly modified
 * by alb (@platypusguy)
 */
public abstract class Option {
    final boolean hasArg;
    final String[] aliases;

    Option(boolean hasArg, String... aliases) {
        this.hasArg = hasArg;
        this.aliases = aliases;
    }

    /**
     * checks whether an option matches any of its aliases
     * @param option the option to check
     * @return whether the option matches an alias
     */
    boolean matches( String option ) {
        for ( String a: aliases ) {
            if ( a.equals( option ))
                return true;
        }
        return false;
    }

    boolean ignoreRest() {
        return false;
    }

    abstract void process(JavapTask task, String opt, String arg) throws JavapTask.BadArgs;


}

