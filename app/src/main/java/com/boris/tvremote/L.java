package com.boris.tvremote;

/** Tiny two-language switch: L.s("по-русски", "in English"). */
public final class L {
    public static volatile boolean en = false;
    private L() { }
    public static String s(String ru, String english) { return en ? english : ru; }
}
