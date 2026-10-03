package de.robv.android.xposed;

public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(String className, ClassLoader classLoader, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return false;
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
    }

    public static int getIntField(Object obj, String fieldName) {
        return 0;
    }

    public static void setIntField(Object obj, String fieldName, int value) {
    }

    public static long getLongField(Object obj, String fieldName) {
        return 0L;
    }

    public static void setLongField(Object obj, String fieldName, long value) {
    }

    public static float getFloatField(Object obj, String fieldName) {
        return 0f;
    }

    public static void setFloatField(Object obj, String fieldName, float value) {
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        return null;
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        return null;
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        return null;
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        return null;
    }
}
