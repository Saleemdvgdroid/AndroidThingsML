package android.love.mlpoc.services;

import android.content.Intent;
import android.love.mlpoc.util.Constants;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class CommandFCMService extends FirebaseMessagingService {

    private Intent broadCastIntent;

    @Override
    public void onCreate() {
        super.onCreate();
        broadCastIntent = new Intent();
        broadCastIntent.setAction(Constants.BROADCAST_ACTION);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        handleInstruction(remoteMessage.getData());

    }

    private void handleInstruction(Map<String, String> data) {
        if(data != null && data.get("toConnect") != null){
            if(data.get("toConnect").equalsIgnoreCase("true")){
                if(data.get("direction")!= null){
                    sendBroadCast(true,data.get("direction"));
                }
            }
            else{
                sendBroadCast(false,"");
            }
        }
    }

    private void sendBroadCast(boolean isStart,String action){

            broadCastIntent.putExtra(Constants.IS_CONNECT,isStart);
            broadCastIntent.putExtra(Constants.DIRECTION,action);
            sendBroadcast(broadCastIntent);
    }
}
