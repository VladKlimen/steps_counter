package com.example.tutorial7;


import androidx.appcompat.app.AppCompatActivity;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;


public class LoadCSV extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load_csv);
        Button BackButton = (Button) findViewById(R.id.button_back);
        LineChart lineChart = (LineChart) findViewById(R.id.line_chart);

        ArrayList<String[]> csvData;

        // our code
        String fileName = "data.csv";
        String showChart = "Magnitude";
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            fileName = extras.getString("file_name");
            showChart = extras.getString("show_chart");
        }


        // end our code

        String path = Environment.getExternalStorageDirectory().getPath() + "/csv_dir/" + fileName;
        try {
            setHeaderText(path);    // our code
        } catch (IOException | CsvValidationException e) {
            e.printStackTrace();
        }
        csvData = CsvRead(path);
        // adjusted for 3 axes or magnitude
//        ArrayList<String[]> axisData = getAxisData(csvData, 1);
        ArrayList<ILineDataSet> dataSets = new ArrayList<>();
        if (showChart.equals("Magnitude")) {
            LineDataSet lineDataSetMagnitude =  new LineDataSet(emptyDataValues(), "Magnitude");
            lineDataSetMagnitude.setColor(Color.rgb(0, 200, 255));
            lineDataSetMagnitude.setCircleColor(Color.rgb(255, 180, 10));

            for (int i = 0; i < csvData.size(); i++) {
                double magnitude = getMagnitude(Float.parseFloat(csvData.get(i)[1]),
                        Float.parseFloat(csvData.get(i)[2]), Float.parseFloat(csvData.get(i)[3]));
                lineDataSetMagnitude.addEntry(new Entry(Float.parseFloat(csvData.get(i)[0]), (float) magnitude));
            }

            dataSets.add(lineDataSetMagnitude);
        }
        else {
            LineDataSet lineDataSet1 =  new LineDataSet(DataValues(getAxisData(csvData, 1)),"X");
            LineDataSet lineDataSet2 =  new LineDataSet(DataValues(getAxisData(csvData, 2)),"Y");
            LineDataSet lineDataSet3 =  new LineDataSet(DataValues(getAxisData(csvData, 3)),"Z");

            lineDataSet1.setColor(Color.rgb(60, 165, 255));
            lineDataSet1.setCircleColor(Color.rgb(60, 165, 255));

            lineDataSet2.setColor(Color.rgb(255, 140, 35));
            lineDataSet2.setCircleColor(Color.rgb(255, 140, 35));

            lineDataSet3.setColor(Color.rgb(80, 255, 50));
            lineDataSet3.setCircleColor(Color.rgb(80, 255, 50));


            dataSets.add(lineDataSet1);
            dataSets.add(lineDataSet2);
            dataSets.add(lineDataSet3);
        }

        LineData data = new LineData(dataSets);
        lineChart.setData(data);
        lineChart.getDescription().setEnabled(false);
        lineChart.getLegend().setEnabled(true);
        lineChart.invalidate();

        BackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClickBack();
            }
        });
    }

    private ArrayList<String[]> getAxisData(ArrayList<String[]> arrayList, int index) {
        ArrayList<String[]> axisData = new ArrayList<>();
        for (int i = 0; i < arrayList.size(); i++) {
            axisData.add(new String[] {arrayList.get(i)[0], arrayList.get(i)[index]});
        }
        return axisData;
    }

    private void ClickBack(){
        finish();

    }

    private ArrayList<String[]> CsvRead(String path){
        ArrayList<String[]> CsvData = new ArrayList<>();
        try {
            File file = new File(path);
            CSVReader reader = new CSVReader(new FileReader(file));
            String[] nextLine;
            for (int i = 0; i < 7; i++) { reader.readNext(); }
            while((nextLine = reader.readNext())!= null){
                CsvData.add(nextLine);
            }

        }catch (Exception ignored){
        }

        return CsvData;
    }

    private void setHeaderText(String path) throws IOException, CsvValidationException {
        File file = new File(path);
        CSVReader reader = new CSVReader(new FileReader(file));
        final TextView fileNameText = findViewById(R.id.textView_file_name);
        final TextView epxTimeText = findViewById(R.id.textView_exp_time);
        final TextView activityTypeText = findViewById(R.id.textView_activity_type);
        final TextView actualStepsText = findViewById(R.id.textView_actual_steps);
        final TextView estimatedStepsText = findViewById(R.id.textView_estimated_steps);

        String text = "File name: " + reader.readNext()[1];
        fileNameText.setText(text);
        text = "Experiment time: " + reader.readNext()[1];
        epxTimeText.setText(text);
        text = "Activity type: " + reader.readNext()[1];
        activityTypeText.setText(text);
        text = "Count of actual steps: " + reader.readNext()[1];
        actualStepsText.setText(text);
        text = "Estimated number of steps: " + reader.readNext()[1];
        estimatedStepsText.setText(text);
    }

    private ArrayList<Entry> DataValues(ArrayList<String[]> csvData){
        ArrayList<Entry> dataVals = new ArrayList<Entry>();
        for (int i = 0; i < csvData.size(); i++){

            dataVals.add(new Entry(Float.parseFloat(csvData.get(i)[0]),
                    Float.parseFloat(csvData.get(i)[1])));


        }

        return dataVals;
    }

    private ArrayList<Entry> emptyDataValues() {
        ArrayList<Entry> dataVals = new ArrayList<Entry>();
        return dataVals;
    }

    private double getMagnitude(float x, float y, float z) {
        return Math.sqrt(Math.pow(x,2) + Math.pow(y,2) + Math.pow(z,2));
    }

}