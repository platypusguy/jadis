/*
 * Copyright (c) 2021 by Andrew Binstock.
 *
 * Portions of this file are copyright Oracle Corp.
 * Those portions are licensed under GPL v. 2.0
 * with the Oracle classpath exception. Due to the
 * requirements of that license, the portions that
 * are copyrighted by Andrew Binstock are obliged
 * to use the same terms and requirements.
 *
 */

package org.jacobin.jadis;

import java.io.PrintWriter;

/*
 * where all the fun begins...
 * @author alb (@platypusguy)
 */
public class Main {

    /**
     * @param args command-line arguments
     */
    public static void main( String[] args ) {
        PrintWriter out = new PrintWriter( System.out );
        JavapTask t = new JavapTask();
        t.setLog( out );
        t.run( args );
    }
}
