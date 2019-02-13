package android.love.mlpoc.util;

import android.content.Context;
import android.net.ConnectivityManager;

public class NetworkUtil {
    public static boolean isConnected(Context context){
        ConnectivityManager connectionManager =(ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return connectionManager.getActiveNetwork() != null;
    }
}
