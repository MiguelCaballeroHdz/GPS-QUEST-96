package android.util;

/** Stub de android.util.Log para el host test. */
public final class Log {

    private Log() {
    }

    public static int i(String tag, String msg) {
        System.out.println("I/" + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.out.println("W/" + tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg, Throwable t) {
        System.out.println("W/" + tag + ": " + msg + " (" + t + ")");
        return 0;
    }

    public static int e(String tag, String msg) {
        System.out.println("E/" + tag + ": " + msg);
        return 0;
    }

    public static int e(String tag, String msg, Throwable t) {
        System.out.println("E/" + tag + ": " + msg + " (" + t + ")");
        return 0;
    }
}
