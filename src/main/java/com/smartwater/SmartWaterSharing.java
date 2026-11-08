package com.smartwater;

import com.google.gson.Gson;
import org.eclipse.paho.client.mqttv3.MqttClient;

public class SmartWaterSharing {

    public static void main(String[] args) {
        System.out.println("Java OK");
        System.out.println("Gson OK : " + new Gson().toJson("test"));
        System.out.println("Paho OK : " + MqttClient.class.getSimpleName());
    }
}