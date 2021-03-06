/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package frc.robot.util;

import static frc.robot.Constants.HUB_CENTER_POSE;
import static frc.robot.Constants.HUB_RADIUS_METERS;
import static frc.robot.Constants.TAPE_STRIP_HEIGHT;
import static frc.robot.Constants.TAPE_STRIP_LENGTH;
import static frc.robot.Constants.TARGET_HEIGHT_METERS;

import java.util.ArrayList;
import java.util.List;

import org.photonvision.SimPhotonCamera;
import org.photonvision.targeting.PhotonTrackedTarget;
import org.photonvision.targeting.TargetCorner;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Transform2d;
import edu.wpi.first.math.util.Units;

public class SimCamera {
    SimPhotonCamera cam;

    SimVisionTarget tgt = new SimVisionTarget(HUB_CENTER_POSE, 
    TARGET_HEIGHT_METERS, TAPE_STRIP_LENGTH, TAPE_STRIP_HEIGHT);

    private Transform2d robotToTargetTrans = new Transform2d();

    double camHorizFOVDegrees;
    double camVertFOVDegrees;
    double cameraHeightOffGroundMeters;
    double maxLEDRangeMeters;
    double camPitchDegrees;
    int cameraResWidth;
    int cameraResHeight;
    double minTargetArea;
    Transform2d cameraToRobot;
    /**
     * Create a simulated vision system involving a camera and coprocessor mounted on a mobile robot
     * running PhotonVision, detecting one or more targets scattered around the field. This assumes a
     * fairly simple and distortion-less pinhole camera model.
     *
     * @param camName Name of the PhotonVision camera to create. Align it with the settings you use in
     *     the PhotonVision GUI.
     * @param camDiagFOVDegrees Diagonal Field of View of the camera used. Align it with the
     *     manufacturer specifications, and/or whatever is configured in the PhotonVision Setting
     *     page.
     * @param camPitchDegrees pitch of the camera's view axis back from horizontal. Make this the same
     *     as whatever is configured in the PhotonVision Setting page.
     * @param cameraToRobot Pose Transform to move from the camera's mount position to the robot's
     *     position
     * @param cameraHeightOffGroundMeters Height of the camera off the ground in meters
     * @param maxLEDRangeMeters Maximum distance at which your camera can illuminate the target and
     *     make it visible. Set to 9000 or more if your vision system does not rely on LED's.
     * @param cameraResWidth Width of your camera's image sensor in pixels
     * @param cameraResHeight Height of your camera's image sensor in pixels
     * @param minTargetArea Minimum area that that the target should be before it's recognized as a
     *     target by the camera. Match this with your contour filtering settings in the PhotonVision
     *     GUI.
     */
    public SimCamera(
            String camName,
            double camDiagFOVDegrees,
            double camPitchDegrees,
            Transform2d cameraToRobot,
            double cameraHeightOffGroundMeters,
            double maxLEDRangeMeters,
            int cameraResWidth,
            int cameraResHeight,
            double minTargetArea) {
        this.camPitchDegrees = camPitchDegrees;
        this.cameraToRobot = cameraToRobot;
        this.cameraHeightOffGroundMeters = cameraHeightOffGroundMeters;
        this.maxLEDRangeMeters = maxLEDRangeMeters;
        this.cameraResWidth = cameraResWidth;
        this.cameraResHeight = cameraResHeight;
        this.minTargetArea = minTargetArea;

        // Calculate horizontal/vertical FOV by similar triangles
        double hypotPixels = Math.hypot(cameraResWidth, cameraResHeight);
        this.camHorizFOVDegrees = camDiagFOVDegrees * cameraResWidth / hypotPixels;
        this.camVertFOVDegrees = camDiagFOVDegrees * cameraResHeight / hypotPixels;
        
        //NetworkTableInstance.getDefault().getTable("photonvision").getEntry("version").setString(PhotonVersion.versionString);
        cam = new SimPhotonCamera(camName);
    }


    /**
     * Adjust the camera position relative to the robot. Use this if your camera is on a gimbal or
     * turret or some other mobile platform.
     *
     * @param newCameraToRobot New Transform from the robot to the camera
     * @param newCamHeightMeters New height of the camera off the floor
     * @param newCamPitchDegrees New pitch of the camera axis back from horizontal
     */
    public void moveCamera(
            Transform2d newCameraToRobot, double newCamHeightMeters, double newCamPitchDegrees) {
        this.cameraToRobot = newCameraToRobot;
        this.cameraHeightOffGroundMeters = newCamHeightMeters;
        this.camPitchDegrees = newCamPitchDegrees;
    }

    /**
     * Periodic update. Call this once per frame of image data you wish to process and send to
     * NetworkTables
     *
     * @param robotPoseMeters current pose of the robot on the field. Will be used to calculate which
     *     targets are actually in view, where they are at relative to the robot, and relevant
     *     PhotonVision parameters.
     */
    public void processFrame(Pose2d robotPoseMeters) {

        ArrayList<PhotonTrackedTarget> visibleTgtList = new ArrayList<>(1);

        Pose2d cameraPos = robotPoseMeters.transformBy(cameraToRobot.inverse());

                    var camToTargetTrans = new Transform2d(cameraPos, tgt.targetPos);

                    double distAlongGroundMeters = camToTargetTrans.getTranslation().getNorm();
                    // Interpolate the target transform 2 feet out from the target towards the camera.
                    camToTargetTrans = new Transform2d(
                        camToTargetTrans.getTranslation().times(
                            (distAlongGroundMeters - HUB_RADIUS_METERS) / distAlongGroundMeters
                        ), camToTargetTrans.getRotation());
                    distAlongGroundMeters = camToTargetTrans.getTranslation().getNorm();
                    double distVerticalMeters =
                            tgt.targetHeightAboveGroundMeters - this.cameraHeightOffGroundMeters;
                    double distMeters = Math.hypot(distAlongGroundMeters, distVerticalMeters);

                    double area = tgt.tgtAreaMeters2 / getM2PerPx(distAlongGroundMeters);

                    // 2D yaw mode considers the target as a point, and should ignore target rotation.
                    // Photon reports it in the correct robot reference frame.
                    // IE: targets to the left of the image should report negative yaw.
                    double yawDegrees =
                            -1.0
                                    * Units.radiansToDegrees(
                                            Math.atan2(
                                                    camToTargetTrans.getTranslation().getY(),
                                                    camToTargetTrans.getTranslation().getX()));
                    double pitchDegrees =
                            Units.radiansToDegrees(Math.atan2(distVerticalMeters, distAlongGroundMeters))
                                    - this.camPitchDegrees;

                    if (camCanSeeTarget(distMeters, yawDegrees, pitchDegrees, area)) {
                        // TOXDO simulate target corners
                        visibleTgtList.add(
                                new PhotonTrackedTarget(
                                        yawDegrees,
                                        pitchDegrees,
                                        area,
                                        0.0,
                                        camToTargetTrans,
                                        List.of(
                                                new TargetCorner(0, 0), new TargetCorner(0, 0),
                                                new TargetCorner(0, 0), new TargetCorner(0, 0))));
                    }
        cam.submitProcessedFrame(0.0, visibleTgtList);
    }

    Transform2d getRobotToTargetTrans() {
        return robotToTargetTrans;
    }

    double getM2PerPx(double dist) {
        double widthMPerPx =
                2 * dist * Math.tan(Units.degreesToRadians(this.camHorizFOVDegrees) / 2) / cameraResWidth;
        double heightMPerPx =
                2 * dist * Math.tan(Units.degreesToRadians(this.camVertFOVDegrees) / 2) / cameraResHeight;
        return widthMPerPx * heightMPerPx;
    }

    boolean camCanSeeTarget(double distMeters, double yaw, double pitch, double area) {
        boolean inRange = (distMeters < this.maxLEDRangeMeters);
        boolean inHorizAngle = Math.abs(yaw) < (this.camHorizFOVDegrees / 2);
        boolean inVertAngle = Math.abs(pitch) < (this.camVertFOVDegrees / 2);
        boolean targetBigEnough = area > this.minTargetArea;
        return (inRange && inHorizAngle && inVertAngle && targetBigEnough);
    }


}
