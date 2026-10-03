package de.robv.android.xposed;

import java.io.File;
import java.util.Map;
import java.util.Set;

public class XSharedPreferences {

    public XSharedPreferences(String packageName, String prefFileName) {
    }

    public XSharedPreferences(File prefFile) {
    }

    public XSharedPreferences(File prefFile, String prefFileName) {
    }

    public void reload() {
    }

    public boolean makeWorldReadable() {
        return false;
    }

    public boolean isReadable() {
        return false;
    }

    public File getFile() {
        return null;
    }

    public boolean contains(String key) {
        return false;
    }

    public Map<String, ?> getAll() {
        return null;
    }

    public String getString(String key, String defValue) {
        return defValue;
    }

    public Set<String> getStringSet(String key, Set<String> defValues) {
        return defValues;
    }

    public int getInt(String key, int defValue) {
        return defValue;
    }

    public long getLong(String key, long defValue) {
        return defValue;
    }

    public float getFloat(String key, float defValue) {
        return defValue;
    }

    public boolean getBoolean(String key, boolean defValue) {
        return defValue;
    }
}
