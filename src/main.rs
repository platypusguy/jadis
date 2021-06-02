/*
 * jadis -- java disassembler (javap-style)
 * (c) copyright 2021 by Andrew Binstock (@platypusguy)
 * home: https://github.com/platypusguy/jadis
 * Open source under Mozilla Public License 2.0
 */
use std::env;

fn main() {
    let args: Vec<String> = env::args().collect();
    println!("{:?}", args);
}
