package com.letstalk.letstalk.fragment;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.letstalk.letstalk.R;
import com.letstalk.letstalk.TextSendListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link TalkFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class TalkFragment extends Fragment {

    // View Handler
    @BindView(R.id.buttonTalk)
    Button button;
    @BindView(R.id.resultTalkBox)
    EditText textResult;
    @BindView(R.id.deleteResultTalkButton)
    ImageButton deleteButton;

    private TextSendListener textSendListener;

    // Bluetooth Section
    private BluetoothAdapter btAdapter;
    private final int START_BLUETOOTH_RC = 10;
    // TODO: FILL MAC ADDRESS OF ARDUINO HERE
    private final String address = "";
    private BluetoothDevice btDevice;
    private BluetoothSocket mmSocket;
    private OutputStream mmOutputStream;
    private InputStream mmInputStream;
    private boolean stopWorker;
    private int readBufferPosition;
    private byte[] readBuffer;
    private Thread workerThread;
    private boolean bluetoothOn = false;

    private MaterialDialog selectMethodDialog;
    private MaterialDialog selectPairedDeviceDialog;
    private MaterialDialog selectNewDeviceDialog;
    private MaterialDialog waitDialog;
    private List<BluetoothDevice> newDevices = new ArrayList<>();

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                //discovery starts, we can show progress dialog or perform other tasks
                waitDialog.show();
                newDevices.clear();
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                //discovery finishes, dismis progress dialog
                waitDialog.dismiss();
                showNewDevice();
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                //bluetooth device found
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                newDevices.add(device);
                Toast.makeText(context, "Find: "+ device.getName() + ", " + device.getAddress(), Toast.LENGTH_SHORT).show();
            }
        }
    };

    public TalkFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of fragment TalkFragment.
     */
    public static TalkFragment newInstance() {
        return new TalkFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_talk, container, false);
        ButterKnife.bind(this, view);
        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (getActivity() instanceof TextSendListener) {
            textSendListener = (TextSendListener) getActivity();
        }
        waitDialog = new MaterialDialog.Builder(Objects.requireNonNull(getActivity()))
                .title(R.string.please_wait)
                .content(R.string.finding_new_device)
                .onAny(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        if (btAdapter != null) {
                            if (btAdapter.isDiscovering()) {
                                btAdapter.cancelDiscovery();
                            }
                        }
                    }
                })
                .progress(true,0)
                .build();
        IntentFilter filter = new IntentFilter();

        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

        Objects.requireNonNull(getActivity()).registerReceiver(mReceiver, filter);
    }

    private void startBluetooth() {
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            Toast.makeText(getActivity(), "Not Support Bluetooth. Can't continue this features.", Toast.LENGTH_SHORT).show();
        } else {
            if (!btAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, START_BLUETOOTH_RC);
            }

            selectMethodDialog = new MaterialDialog
                    .Builder(Objects.requireNonNull(getActivity()))
                    .title(R.string.select_method)
                    .items(R.array.method_selection)
                    .itemsCallbackSingleChoice(-1, new MaterialDialog.ListCallbackSingleChoice() {
                        @Override
                        public boolean onSelection(MaterialDialog dialog, View view, int which, CharSequence text) {
                            if (which == 0) {
                                // Paired Device
                                showPairedDevice();
                            } else {
                                // Find New Device
                                findNewDevice();
                            }
                            return true;
                        }
                    })
                    .positiveText(R.string.ok)
                    .build();
            selectMethodDialog.show();
        }
    }

    private void findNewDevice() {
        if (btAdapter.isDiscovering()) {
            // Bluetooth is already in modo discovery mode, we cancel to restart it again
            btAdapter.cancelDiscovery();
        }
        btAdapter.startDiscovery();
    }

    private void showNewDevice() {
        if (newDevices.size() > 0) {
            List<String> newDevicesName = new ArrayList<>();
            for (BluetoothDevice device : newDevices) {
                newDevicesName.add(device.getName());
            }
            selectNewDeviceDialog = new MaterialDialog.Builder(Objects.requireNonNull(getActivity()))
                    .title(R.string.select_new_devices)
                    .items(newDevicesName)
                    .itemsCallbackSingleChoice(-1, new MaterialDialog.ListCallbackSingleChoice() {
                        @Override
                        public boolean onSelection(MaterialDialog dialog, View itemView, int which, CharSequence text) {
                            if (which >= newDevices.size()) {
                                Toast.makeText(getActivity(), "False Reaction", Toast.LENGTH_SHORT).show();
                            } else {
                                btDevice = newDevices.get(which);
                                startReceive();
                            }
                            return true;
                        }
                    })
                    .build();
            selectNewDeviceDialog.show();
        } else {
            Toast.makeText(getActivity(),"No Devices Found.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPairedDevice() {
        final Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
        List<String> devicesName = new ArrayList<>();
        final List<BluetoothDevice> devices = new ArrayList<>();
        if (pairedDevices.size() > 0 ) {
            for (BluetoothDevice device : pairedDevices) {
                devicesName.add(device.getName());
                devices.add(device);
            }
            selectPairedDeviceDialog = new MaterialDialog
                    .Builder(Objects.requireNonNull(getActivity()))
                    .title(R.string.select_paired_device)
                    .items(devicesName)
                    .itemsCallbackSingleChoice(-1, new MaterialDialog.ListCallbackSingleChoice() {
                        @Override
                        public boolean onSelection(MaterialDialog dialog, View itemView, int which, CharSequence text) {
                            if (which >= pairedDevices.size()) {
                                Toast.makeText(getActivity(), "False Reaction", Toast.LENGTH_SHORT).show();
                            } else {
                                btDevice = devices.get(which);
                                startReceive();
                            }
                            return true;
                        }
                    })
                    .build();
            selectPairedDeviceDialog.show();
        } else {
            Toast.makeText(getActivity(),"Never connected to bluetooth device.", Toast.LENGTH_SHORT).show();
        }
    }

    private void startReceive() {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID
        try {
            mmSocket = btDevice.createRfcommSocketToServiceRecord(uuid);
            mmSocket.connect();
            mmOutputStream = mmSocket.getOutputStream();
            mmInputStream = mmSocket.getInputStream();
            changeButtonToStop();
            beginListenForData();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getActivity(),"Can't Create Connection to this device", Toast.LENGTH_SHORT).show();
        }
    }

    @OnClick(R.id.buttonTalk)
    void clickStartBluetooth() {
        if (!bluetoothOn) {
            startBluetooth();
        } else {
            stopReceive();
        }
    }

    private void changeButtonToStop() {
        bluetoothOn = true;
        button.setText(R.string.stop);
        button.setBackgroundColor(Color.parseColor("#FF0000"));
    }

    private void changeButtonToStart() {
        bluetoothOn = false;
        button.setText(R.string.button_talk);
        button.setBackgroundColor(getResources().getColor(R.color.buttonColor));
    }

    private void beginListenForData() {
        Toast.makeText(getActivity(),"Listening Data from Arduino", Toast.LENGTH_SHORT).show();

        final Handler handler = new Handler();
        final byte delimiter = 10; //This is the ASCII code for a newline character

        stopWorker = false;
        readBufferPosition = 0;
        readBuffer = new byte[1024];
        workerThread = new Thread(new Runnable() {
            public void run()
            {
                while(!Thread.currentThread().isInterrupted() && !stopWorker)
                {
                    try
                    {
                        int bytesAvailable = mmInputStream.available();
                        if(bytesAvailable > 0)
                        {
                            byte[] packetBytes = new byte[bytesAvailable];
                            mmInputStream.read(packetBytes);
                            for(int i=0;i<bytesAvailable;i++)
                            {
                                byte b = packetBytes[i];
                                if(b == delimiter)
                                {
                                    byte[] encodedBytes = new byte[readBufferPosition];
                                    System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    final String data = new String(encodedBytes, "US-ASCII");
                                    readBufferPosition = 0;

                                    handler.post(new Runnable()
                                    {
                                        public void run()
                                        {
                                            textResult.setText(data);
                                        }
                                    });
                                }
                                else
                                {
                                    readBuffer[readBufferPosition++] = b;
                                }
                            }
                        }
                    }
                    catch (IOException ex)
                    {
                        stopWorker = true;
                    }
                }
            }
        });

        workerThread.start();
    }

    @Override
    public void onStop() {
        super.onStop();
        stopReceive();
    }

    @Override
    public void onDestroyView() {
        Objects.requireNonNull(getActivity()).unregisterReceiver(mReceiver);
        super.onDestroyView();
    }

    private void stopReceive() {
        stopWorker = true;
        try {
            if (mmOutputStream!= null) {
                mmOutputStream.close();
            }
            if (mmInputStream != null) {
                mmInputStream.close();
            }
            if (mmSocket != null) {
                mmSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @OnClick(R.id.talkButton)
    void clickTalk() {
        String text = textResult.getText().toString();
        if (textSendListener != null) {
            textSendListener.callSpeech(text);
        }
    }

    @OnClick(R.id.deleteResultTalkButton)
    void clearText() {
        textResult.getText().clear();
    }

}