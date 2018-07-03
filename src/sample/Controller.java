package sample;

import javafx.fxml.FXML;
import javafx.scene.control.Slider;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.opencv.core.CvType.CV_8U;
import static org.opencv.imgproc.Imgproc.*;

public class Controller {

    @FXML
    private Slider thresholdSlider;
    @FXML
    private Slider blurSlider;

    @FXML
    private ImageView view1;
    @FXML
    private ImageView view2;
    @FXML
    private ImageView view3;

    private ScheduledExecutorService timer;

    private VideoCapture capture;

    private boolean videoActive = false;

    private int totalDiceValue = 0;

    protected void init() {
        this.capture = new VideoCapture();
        view1.setPreserveRatio(true);
        startCamera();
    }

    @FXML
    protected void startCamera() {

        if (!this.videoActive) {

            //Open the video
            this.capture.open("./resources/dice.mp4");

            if (this.capture.isOpened()) {
                this.videoActive = true;
                Runnable frameGrabber = new Runnable() {
                    @Override
                    public void run() {
                        Mat originalFrame = grabOriginalFrame();
                        Mat greyscaleFrame = convertFrameToGreyscale(originalFrame);
                        Mat thresholdFrame = applyThreshold(greyscaleFrame);
                        Mat resultFrame = detectDice(originalFrame, thresholdFrame);
                        displayFrame(resultFrame, view1);

                        //Loop the video (if frame position == number of frames)
                        if(capture.get(1) == capture.get(7)-1) {
                            capture.set(1, 0);
                        }
                    }
                };

                this.timer = Executors.newSingleThreadScheduledExecutor();
                this.timer.scheduleAtFixedRate(frameGrabber, 0, 33, TimeUnit.MILLISECONDS);
            } else {
                System.err.println("Cannot open camera!");
            }
        } else {
            this.videoActive = false;

            this.stopAcquisition();
        }
    }

    private Mat grabOriginalFrame() {
        Mat frame = new Mat();

        if (this.capture.isOpened()) {
            try {
                this.capture.read(frame);
            } catch (Exception e) {
                System.err.println("Couldn't grab the frame!");
            }
        }
        return frame;
    }

    private Mat convertFrameToGreyscale(Mat frame) {
        Mat grayFrame = new Mat();
        Imgproc.cvtColor(frame, grayFrame, Imgproc.COLOR_BGR2GRAY);
        applyBlur(grayFrame);
        return grayFrame;
    }

    private Mat applyThreshold(Mat frame) {
        Mat thresholdFrame = new Mat();
        Imgproc.threshold(frame, thresholdFrame, thresholdSlider.getValue(), 255, Imgproc.THRESH_BINARY);
        return thresholdFrame;
    }

    private void applyBlur(Mat frame) {
        for(int i = 0; i < blurSlider.getValue(); i++) {
            Imgproc.blur(frame, frame, new Size(5.0, 5.0));
        }
    }

    private Mat detectDice(Mat originalFrame, Mat thresholdFrame) {
        //Find and save contours
        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
        findContours(thresholdFrame, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<MatOfPoint> polys = generatePolysFromContours(contours);

        totalDiceValue = 0;

        for(MatOfPoint poly : polys) {
            if(poly.size().height == 4) {
                Mat dice = getWarpedDice(poly, thresholdFrame);
                Mat diceWithEffects = new Mat();

                int currentDiceValue = calculateDiceValue(dice, diceWithEffects);
                totalDiceValue += currentDiceValue;

                //displayFrame(dice, view2);
                //displayFrame(diceWithEffects, view3);

                putText(originalFrame, "v: " + currentDiceValue, poly.toList().get(3), 0 , 1, new Scalar(255,255,255), 2);
            }
        }

        putText(originalFrame, "Total value: " + totalDiceValue, new Point(300, 650), 0 , 2, new Scalar(255,255,255), 4);

        return originalFrame;
    }

    private static ArrayList<MatOfPoint> generatePolysFromContours(List<MatOfPoint> cnts) {
        ArrayList<MatOfPoint2f> opened = new ArrayList<>();

        //Convert MatOfPoint to MatOfPoint2f
        for (MatOfPoint m : cnts) {
            MatOfPoint2f temp = new MatOfPoint2f(m.toArray());
            opened.add(temp);
        }

        //Apply approxPolyDP
        ArrayList<MatOfPoint> closed = new ArrayList<>();
        for (MatOfPoint2f conts : opened) {
            MatOfPoint2f temp = new MatOfPoint2f();
            Imgproc.approxPolyDP(conts, temp, 10, true);
            MatOfPoint closedTemp = new MatOfPoint(temp.toArray());
            closed.add(closedTemp);
        }
        return closed;
    }

    private Mat getWarpedDice(MatOfPoint poly, Mat frame) {
        MatOfPoint2f srcRect = new MatOfPoint2f();
        MatOfPoint2f destRect = new MatOfPoint2f();
        Mat dice = new Mat(500, 500, frame.type());

        srcRect = new MatOfPoint2f(poly.toList().get(0), poly.toList().get(1), poly.toList().get(2), poly.toList().get(3));
        destRect = new MatOfPoint2f(new Point(0,0), new Point(0, 500), new Point (500, 500), new Point (500, 0));

        Mat transform = getPerspectiveTransform(srcRect, destRect);
        warpPerspective(frame, dice, transform, dice.size());

        return dice;
    }

    private int calculateDiceValue(Mat dice, Mat diceWithEffects) {
        Imgproc.resize(dice, dice, new Size(500, 500));

        int diceValue = 0;

        //Distance transform
        distanceTransform(dice, diceWithEffects, CV_DIST_L2, 3);

        //Convert and threshold
        diceWithEffects.convertTo(diceWithEffects, CV_8U);
        threshold(diceWithEffects, diceWithEffects, 10, 255, THRESH_BINARY);

        //Dilate
        Mat kernel = Mat.ones(new Size (5,5), diceWithEffects.type());
        dilate(diceWithEffects, diceWithEffects, kernel, new Point(-1, -1), 15);

        //Detect contours
        List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
        findContours(diceWithEffects, contours, new Mat(), RETR_LIST, CHAIN_APPROX_SIMPLE);

        //Use contour areas to count pips
        double[] contourAreas = new double[contours.size()];
        for(int i = 0; i < contours.size(); i++) {
            contourAreas[i] = contourArea(contours.get(i));
            if(contourAreas[i] < 5000) {
                diceValue++;
            }
        }

        return diceValue;
    }

    private void displayFrame(Mat frame, ImageView view) {
        Image image = it.polito.elite.teaching.cv.utils.Utils.mat2Image(frame);
        if (image != null) {
            updateImageView(view, image);
        }
        frame.release();
    }

    private void stopAcquisition() {
        if (this.timer != null && !this.timer.isShutdown()) {
            try {
                this.timer.shutdown();
                this.timer.awaitTermination(33, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                System.err.println("Error when trying to stop the frame capture!");
            }
        }
        if (this.capture.isOpened()) {
            this.capture.release();
        }
    }

    private void updateImageView(ImageView view, Image image) {
        it.polito.elite.teaching.cv.utils.Utils.onFXThread(view.imageProperty(), image);
    }

    protected void setClosed() {
        this.stopAcquisition();
    }
}