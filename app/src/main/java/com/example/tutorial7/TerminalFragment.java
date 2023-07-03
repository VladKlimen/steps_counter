package com.example.tutorial7;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvValidationException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected {False, Pending, True}

    private String deviceAddress;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;

    LineChart mpLineChart;
    LineDataSet lineDataSet1;
    LineDataSet lineDataSet2;
    LineDataSet lineDataSet3;
    LineDataSet lineDataSetMagnitude;
    ArrayList<ILineDataSet> dataSets = new ArrayList<>();

    LineData data;
    LineData magnitudeData;
    ArrayList<Double> magnitudeArrayList = new ArrayList<>();
    ArrayList<Double> movingAverageList = new ArrayList<>();
    ArrayList<String[]> preservedData = new ArrayList<>();
    String csvFileName = "data.csv";
    String csvPath = Environment.getExternalStorageDirectory().getPath() + "/csv_dir/" + csvFileName;
    boolean started = false;

    private EditText file_name_text;
    private EditText steps_count_text;
    private RadioButton walk_button;
    private RadioButton run_button;

    private String showChart = "Magnitude"; // options: Magnitude, Acceleration

    Python py =  Python.getInstance();
    PyObject pyobj = py.getModule("test");

    Integer estimatedSteps = 0;
    private TextView estimatedStepsText;

    private final Integer MOVING_AVG_WINDOW_SIZE = 10;

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");

    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if (started) {
            started = false;    // our code
            send("STOP");
        }
        if (service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try {
            getActivity().unbindService(this);
        } catch (Exception ignored) {
        }
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if (initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));

        mpLineChart = (LineChart) view.findViewById(R.id.line_chart);
//        lineDataSet1 = new LineDataSet(emptyDataValues(), "Temperature");
        lineDataSet1 = new LineDataSet(emptyDataValues(), "X");
        lineDataSet2 = new LineDataSet(emptyDataValues(), "Y");
        lineDataSet3 = new LineDataSet(emptyDataValues(), "Z");

        lineDataSetMagnitude = new LineDataSet(emptyDataValues(), "Magnitude");

        lineDataSetMagnitude.setColor(Color.rgb(0, 200, 255));
        lineDataSetMagnitude.setCircleColor(Color.rgb(255, 180, 10));

        lineDataSet1.setColor(Color.rgb(60, 165, 255));
        lineDataSet1.setCircleColor(Color.rgb(60, 165, 255));

        lineDataSet2.setColor(Color.rgb(255, 140, 35));
        lineDataSet2.setCircleColor(Color.rgb(255, 140, 35));

        lineDataSet3.setColor(Color.rgb(80, 255, 50));
        lineDataSet3.setCircleColor(Color.rgb(80, 255, 50));

        dataSets.add(lineDataSet1);
        dataSets.add(lineDataSet2);
        dataSets.add(lineDataSet3);

        data = new LineData(dataSets);

        ArrayList<ILineDataSet> magnitudeSets = new ArrayList<>();
        magnitudeSets.add(lineDataSetMagnitude);
        magnitudeData = new LineData(magnitudeSets);

        if (showChart.equals("Magnitude")) {
            mpLineChart.setData(magnitudeData);
        }
        else {
            mpLineChart.setData(data);
        }

        mpLineChart.getDescription().setEnabled(false);
        mpLineChart.getLegend().setEnabled(true);
        mpLineChart.invalidate();

        Button buttonClear = (Button) view.findViewById(R.id.clear_button);
        Button buttonCsvShow = (Button) view.findViewById(R.id.openCSV_button);
        Button buttonStart = (Button) view.findViewById(R.id.start_button);
        Button buttonStop = (Button) view.findViewById(R.id.stop_button);
        Button buttonReset = (Button) view.findViewById(R.id.reset_button);
        Button buttonSave = (Button) view.findViewById(R.id.save_button);

        file_name_text = view.findViewById(R.id.file_name_text);
        steps_count_text = view.findViewById(R.id.steps_count_text);
        walk_button = view.findViewById(R.id.walking_radioButton);
        run_button = view.findViewById(R.id.running_radioButton);

        estimatedStepsText = view.findViewById(R.id.textView_estimated_steps);


        buttonClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(getContext(), "Clear", Toast.LENGTH_SHORT).show();
                removeData(true);
                refreshGraph();
            }
        });

        buttonCsvShow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    OpenLoadCSV();
                } catch (IOException | CsvValidationException e) {
                    e.printStackTrace();
                    Toast.makeText(getContext(), "There is no data!", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // our code: additional buttons
        buttonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!started) {
                    if (connected != Connected.True) {
                        Toast.makeText(getActivity(), "Not connected", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    send("START");
                    started = true;
                    Toast.makeText(getContext(), "Starting...", Toast.LENGTH_SHORT).show();
                }
                else {
                    Toast.makeText(getContext(), "Already started", Toast.LENGTH_SHORT).show();
                }
            }
        });

        buttonStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (started) {
                    send("STOP");
                    started = false;
                    Toast.makeText(getContext(), "Stopping...", Toast.LENGTH_SHORT).show();
                }
                else {
                    Toast.makeText(getContext(), "Already stopped", Toast.LENGTH_SHORT).show();
                }
            }
        });

        buttonReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                started = false;
                send("RESET");
                removeData(false);
                refreshGraph();
                removePreservedData();
                estimatedSteps = 0;
                String text = "Current estimated number of steps: 0";
                estimatedStepsText.setText(text);
                Toast.makeText(getContext(), "Reset", Toast.LENGTH_SHORT).show();
            }
        });

        buttonSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveData();
            }
        });

        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        menu.findItem(R.id.hex).setChecked(hexEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private String[] clean_str(String[] stringsArr) {
        for (int i = 0; i < stringsArr.length; i++) {
            stringsArr[i] = stringsArr[i].replaceAll(" ", "");
        }
        return stringsArr;
    }

    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(requireActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str) {
        if (connected != Connected.True) {
            Toast.makeText(getActivity(), "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if (hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void receive(byte[] message) {
        if (hexEnabled) {
            receiveText.append(TextUtil.toHexString(message) + '\n');
        }
        else {
            String msg = new String(message);

            if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                // don't show CR as ^M if directly before LF
                String msg_to_save = msg;
                msg_to_save = msg.replace(TextUtil.newline_crlf, TextUtil.emptyString);
                // check message length
                if (msg_to_save.length() > 1) {
                    // split message string by ',' char
                    String[] parts = msg_to_save.split(",");
                    // function to trim blank spaces
                    clean_str(parts);

                    // saving data to csv
                    try {
                        float time = Float.parseFloat(parts[0]);

                        // OUR CODE for tree lines in one chart
                        data.addEntry(new Entry(time, Float.parseFloat(parts[1])), 0);
                        data.addEntry(new Entry(time, Float.parseFloat(parts[2])), 1);
                        data.addEntry(new Entry(time, Float.parseFloat(parts[3])), 2);
                        // OUR CODE for magnitude chart
                        double magnitude = getMagnitude(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
                        magnitudeData.addEntry(new Entry(time, (float) movingAverage(movingAverageList, magnitude, MOVING_AVG_WINDOW_SIZE)), 0);
                        magnitudeArrayList.add(movingAverage(movingAverageList, magnitude, MOVING_AVG_WINDOW_SIZE));
                        refreshGraph();

                        double[] magnitudeArray = magnitudeArrayList.stream().mapToDouble(d -> d).toArray();
                        // pass magnitude data to python
                        PyObject obj = pyobj.callAttr("main", (Object) magnitudeArray);
                        estimatedSteps = obj.toInt();
                        String text = "Current estimated number of steps: " + estimatedSteps.toString();
                        estimatedStepsText.setText(text);



                    } catch (IllegalArgumentException ignored) {
                    }
                }

                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                // send msg to function that saves it to csv
                // special handling if CR and LF come in separate fragments
                if (pendingNewline && msg.charAt(0) == '\n') {
                    Editable edt = receiveText.getEditableText();
                    if (edt != null && edt.length() > 1)
                        edt.replace(edt.length() - 2, edt.length(), "");
                }
                pendingNewline = msg.charAt(msg.length() - 1) == '\r';
            }
            receiveText.append(TextUtil.toCaretString(msg, newline.length() != 0));
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        started = false; // our code
        disconnect();
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onSerialRead(byte[] data) {
        try {
            if (started)    // our code
                receive(data);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        started = false; // our code
        disconnect();
    }

    private ArrayList<Entry> emptyDataValues() {
        return new ArrayList<Entry>();
    }

    private void OpenLoadCSV() throws IOException, CsvValidationException {
        getCsvFilename();
        File file = new File(csvPath);
        CSVReader reader = new CSVReader(new FileReader(file));

        for (int i = 0; i < 7; i++) { reader.readNext(); }  // skip the header
        if (reader.readNext() == null) {
            Toast.makeText(getContext(), "There is no data!", Toast.LENGTH_SHORT).show();
        }
        else {
            Intent intent = new Intent(getContext(), LoadCSV.class);
            intent.putExtra("file_name", csvFileName);   // our code
            intent.putExtra("show_chart", showChart);   // our code
            startActivity(intent);
        }
    }

    @NonNull
    private String getCsvFilename() {
        String file_text = file_name_text.getText().toString().trim();
        if (file_text.length() != 0) {
            csvFileName = file_text + ".csv";
            csvPath = Environment.getExternalStorageDirectory().getPath() + "/csv_dir/" + csvFileName;
        }
        return csvFileName;
    }

    private void saveData() {
        String num_steps_str = steps_count_text.getText().toString().trim();
        if (num_steps_str.length() == 0) {
            Toast.makeText(getContext(), "Enter Number of Steps!", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean walk = walk_button.isChecked();
        boolean run = run_button.isChecked();
        if (!walk && !run) {
            Toast.makeText(getContext(), "Choose activity type!", Toast.LENGTH_SHORT).show();
            return;
        }
        String activity = walk ? "Walking" : "Running";

        String folder_str = Environment.getExternalStorageDirectory().getPath() + "/csv_dir/";
        File folder = new File(folder_str);
        Log.println(Log.INFO, "mkdirs", String.valueOf(folder.mkdirs()));
        String filename = getCsvFilename();
        String file_path = folder_str + filename;
        File file = new File(file_path);

        if (file.exists()) {
            new AlertDialog.Builder(getContext())
                    .setTitle("Existing File")
                    .setMessage("The file \"" + filename + "\" already exists, do you want to overwrite it?")
                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            writeCsvHeader(filename, activity, num_steps_str, file_path);
                            saveDataToCSV();
                            Toast.makeText(getContext(), "Saved (overwrite)", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                        }
                    })
                    .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            saveDataToCSV();
                            Toast.makeText(getContext(), "Saved (append)", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
        }
        else {
            writeCsvHeader(filename, activity, num_steps_str, file_path);
            saveDataToCSV();
            Toast.makeText(getContext(), "Saved", Toast.LENGTH_SHORT).show();
        }

    }

    private void writeCsvHeader(String filename, String activity, String num_steps_str, String file_path) {
        try {
//            NAME:,csv_format.csv,,
//            EXPERIMENT TIME:,11/5/2023 15:00,,
//            ACTIVITY TYPE:,Running,,
//            COUNT OF ACTUAL STEPS:,150,,
//            ,,,
            CSVWriter csvWriter = new CSVWriter(new FileWriter(file_path, false),
                    ',',
                    CSVWriter.NO_QUOTE_CHARACTER,
                    CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                    CSVWriter.RFC4180_LINE_END);

            SimpleDateFormat sdf = new SimpleDateFormat("dd/M/yyyy HH:mm", Locale.getDefault());
            String currentDateAndTime = sdf.format(new Date());

            csvWriter.writeNext(new String[]{"NAME:", filename, new String(""), new String("")});
            csvWriter.writeNext(new String[]{"EXPERIMENT TIME:", currentDateAndTime, new String(""), new String("")});
            csvWriter.writeNext(new String[]{"ACTIVITY TYPE:", activity, new String(""), new String("")});
            csvWriter.writeNext(new String[]{"COUNT OF ACTUAL STEPS:", num_steps_str, new String(""), new String("")});
            csvWriter.writeNext(new String[]{"ESTIMATED NUMBER OF STEPS:", Integer.toString(estimatedSteps), new String(""), new String("")}); // for lab7
            csvWriter.writeNext(new String[]{new String(""), new String(""), new String(""), new String("")});
            csvWriter.writeNext(new String[]{"Time [sec]", "ACC X", "ACC Y", "ACC Z"});
            csvWriter.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveDataToCSV() {
        CSVWriter csvWriter = null;
        try {
            csvWriter = new CSVWriter(new FileWriter(csvPath, true),
            ',',
                    CSVWriter.NO_QUOTE_CHARACTER,
                    CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                    CSVWriter.RFC4180_LINE_END);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // write the preserved
        for (String[] datum : preservedData) {
            assert csvWriter != null;
            csvWriter.writeNext(datum);
        }

        // write the rest
        List<ILineDataSet> datasets = data.getDataSets();
        ILineDataSet datasetX = datasets.get(0);
        ILineDataSet datasetY = datasets.get(1);
        ILineDataSet datasetZ = datasets.get(2);

        for (int i = 0; i < datasetX.getEntryCount(); i++) {
            float _time = datasetX.getEntryForIndex(i).getX();
            float _X = datasetX.getEntryForIndex(i).getY();
            float _Y = datasetY.getEntryForIndex(i).getY();
            float _Z = datasetZ.getEntryForIndex(i).getY();

            //OUR CODE: PARSING TO GYRO MSG: [0] is time, [1] is X, [2] is Y, [3] is Z
            String[] row = new String[]{Float.toString(_time), Float.toString(_X), Float.toString(_Y), Float.toString(_Z)};
            assert csvWriter != null;
            csvWriter.writeNext(row);
        }

        try {
            assert csvWriter != null;
            csvWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void lineDataToArray (LineData data) {
        List<ILineDataSet> datasets = data.getDataSets();
        ILineDataSet datasetX = datasets.get(0);
        ILineDataSet datasetY = datasets.get(1);
        ILineDataSet datasetZ = datasets.get(2);

        for (int i = 0; i < datasetX.getEntryCount(); i++) {
            float _time = datasetX.getEntryForIndex(i).getX();
            float _X = datasetX.getEntryForIndex(i).getY();
            float _Y = datasetY.getEntryForIndex(i).getY();
            float _Z = datasetZ.getEntryForIndex(i).getY();

            //OUR CODE: PARSING TO GYRO MSG: [0] is time, [1] is X, [2] is Y, [3] is Z
            String[] row = new String[]{Float.toString(_time), Float.toString(_X), Float.toString(_Y), Float.toString(_Z)};
            preservedData.add(row);
        }
    }

    private void refreshGraph() {
        if (showChart.equals("Magnitude")) {
            lineDataSetMagnitude.notifyDataSetChanged();
        }
        else {
            lineDataSet1.notifyDataSetChanged(); // let the data know a dataSet changed
            lineDataSet2.notifyDataSetChanged();
            lineDataSet3.notifyDataSetChanged();
        }

        mpLineChart.notifyDataSetChanged(); // let the chart know it's data changed
        mpLineChart.invalidate(); // refresh
    }

    private void removeData(boolean preserve) {
        if (preserve) {
            lineDataToArray(data);
        }
        for (int i = 0; i < 3; i++) {
            ILineDataSet set = data.getDataSetByIndex(i);
            data.getDataSetByIndex(i);
            while (set.removeLast()) {
            }
        }
        ILineDataSet set = magnitudeData.getDataSetByIndex(0);
        while (set.removeLast()) {
        }
        magnitudeArrayList = new ArrayList<>();
        movingAverageList = new ArrayList<>();
    }

    private void removePreservedData () {
        preservedData = new ArrayList<>();
    }

    private double getMagnitude(float x, float y, float z) {
        return Math.sqrt(Math.pow(x,2) + Math.pow(y,2) + Math.pow(z,2));
    }

    public static double movingAverage(List<Double> values, double newValue, int n) {
        if (values.size() >= n) {
            values.remove(0);
        }
        values.add(newValue);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return values.stream().mapToDouble(i -> i).average().orElse(0.0);
        }
        return newValue;
    }

}


