package frc.robot.subsystems;

import static frc.robot.Constants.CAN_ID_BACK_LEFT_DRIVE_MOTOR;
import static frc.robot.Constants.CAN_ID_BACK_RIGHT_DRIVE_MOTOR;
import static frc.robot.Constants.CAN_ID_FRONT_LEFT_DRIVE_MOTOR;
import static frc.robot.Constants.CAN_ID_FRONT_RIGHT_DRIVE_MOTOR;
import static frc.robot.Constants.DRIVEBASE_DEADBAND;
import static frc.robot.Constants.DRIVEBASE_ENCODER_ROTATIONS_PER_WHEEL_ROTATION;
import static frc.robot.Constants.DRIVEBASE_FWD_BACK_SLEW_LIMIT;
import static frc.robot.Constants.DRIVEBASE_GEARBOX;
import static frc.robot.Constants.DRIVEBASE_KINEMATICS;
import static frc.robot.Constants.DRIVEBASE_LINEAR_FF_MPS;
import static frc.robot.Constants.DRIVEBASE_METERS_PER_WHEEL_ROTATION;
import static frc.robot.Constants.DRIVEBASE_P;
import static frc.robot.Constants.DRIVEBASE_PLANT;
import static frc.robot.Constants.DRIVEBASE_SIM_ENCODER_STD_DEV;
import static frc.robot.Constants.DRIVEBASE_TRACKWIDTH;
import static frc.robot.Constants.DRIVEBASE_TURN_SLEW_LIMIT;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import com.kauailabs.navx.frc.AHRS;
import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMax.IdleMode;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;

import edu.wpi.first.hal.SimDouble;
import edu.wpi.first.hal.simulation.SimDeviceDataJNI;
import edu.wpi.first.math.MathUtil;
import edu.wpi.first.math.controller.PIDController;
import edu.wpi.first.math.controller.RamseteController;
import edu.wpi.first.math.controller.SimpleMotorFeedforward;
import edu.wpi.first.math.filter.SlewRateLimiter;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.DifferentialDriveOdometry;
//import frc.robot.util.pose.DifferentialDriveOdometry;
import edu.wpi.first.math.kinematics.DifferentialDriveWheelSpeeds;
import edu.wpi.first.math.trajectory.Trajectory;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj.SerialPort.Port;
import edu.wpi.first.wpilibj.drive.DifferentialDrive;
import edu.wpi.first.wpilibj.drive.DifferentialDrive.WheelSpeeds;
import edu.wpi.first.wpilibj.simulation.DifferentialDrivetrainSim;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.InstantCommand;
import edu.wpi.first.wpilibj2.command.RamseteCommand;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.Constants;
import frc.robot.util.NomadMathUtil;
import frc.robot.util.SimEncoder;
import frc.robot.util.command.RunEndCommand;
import io.github.oblarg.oblog.Loggable;
import io.github.oblarg.oblog.annotations.Log;

public class DrivebaseS extends SubsystemBase implements Loggable {
  /**
   * The odometry object, which checks the encoder readings and gyro every loop and calculates the robot's change in pose
   * to keep an estimate of the field-relative position of the robot.
   */
  DifferentialDriveOdometry odometry = new DifferentialDriveOdometry(new Rotation2d());

  // Motor controllers.
  private final CANSparkMax frontRight = new CANSparkMax(CAN_ID_FRONT_RIGHT_DRIVE_MOTOR, MotorType.kBrushless);
  private final CANSparkMax frontLeft = new CANSparkMax(CAN_ID_FRONT_LEFT_DRIVE_MOTOR, MotorType.kBrushless);
  private final CANSparkMax backRight = new CANSparkMax(CAN_ID_BACK_RIGHT_DRIVE_MOTOR, MotorType.kBrushless);
  private final CANSparkMax backLeft = new CANSparkMax(CAN_ID_BACK_LEFT_DRIVE_MOTOR, MotorType.kBrushless);

  /* Slew rate limiters. These filters limit the rate of change of the joystick axes. If the parameter is n,
  these limit the axes to changing no faster than "0 to full in n seconds" */
  private final SlewRateLimiter fwdBackLimiter = new SlewRateLimiter(DRIVEBASE_FWD_BACK_SLEW_LIMIT);
  private final SlewRateLimiter turnLimiter = new SlewRateLimiter(DRIVEBASE_TURN_SLEW_LIMIT);

  /**
   * Feedforwards for both drivebase sides in meters per second.
   */
  public final SimpleMotorFeedforward leftFF = new SimpleMotorFeedforward(DRIVEBASE_LINEAR_FF_MPS[0], DRIVEBASE_LINEAR_FF_MPS[1], DRIVEBASE_LINEAR_FF_MPS[2]);
  public final SimpleMotorFeedforward rightFF = new SimpleMotorFeedforward(DRIVEBASE_LINEAR_FF_MPS[0], DRIVEBASE_LINEAR_FF_MPS[1], DRIVEBASE_LINEAR_FF_MPS[2]);

  /**
   * Velocity PID controllers for both drivebase sides.
   */
  public final PIDController leftPID = new PIDController(DRIVEBASE_P, 0, 0);
  public final PIDController rightPID = new PIDController(DRIVEBASE_P, 0, 0);

  /**
   * The gyro sensor. This sits in the middle of the bot and tracks the heading (turning angle) of the robot.
   */
  private final AHRS navX = new AHRS(Port.kMXP);

  /**
   * The Ramsete Controller for path following. This commands drivetrain velocities in order to drive the robot
   * to the next pose on the trajectory.
   */
  public final RamseteController ramseteController = new RamseteController();

  /**
   * The initial pose of our 4-ball auto, in meters. See the discussion of field-relative coordinates in Trajectories.java
   */
  public final Pose2d START_POSE = new Pose2d (6.67436, 2.651410, Rotation2d.fromDegrees(-155.055217));

  //Sim stuff
  private DifferentialDrivetrainSim m_driveSim;
  @Log(methodName = "getPosition", name = "getSimRightPosition")
  private SimEncoder m_rightEncoder = new SimEncoder();
  @Log(methodName = "getPosition", name = "getSimLeftPosition")
  private SimEncoder m_leftEncoder = new SimEncoder();


  /** Creates a new DrivebaseS. */
  public DrivebaseS() {
    frontRight.restoreFactoryDefaults();
    frontLeft.restoreFactoryDefaults();

    frontRight.getEncoder().setPositionConversionFactor(DRIVEBASE_METERS_PER_WHEEL_ROTATION 
    / DRIVEBASE_ENCODER_ROTATIONS_PER_WHEEL_ROTATION);
    frontRight.getEncoder().setVelocityConversionFactor(DRIVEBASE_METERS_PER_WHEEL_ROTATION 
    / DRIVEBASE_ENCODER_ROTATIONS_PER_WHEEL_ROTATION / 60);
    frontLeft.getEncoder().setPositionConversionFactor(DRIVEBASE_METERS_PER_WHEEL_ROTATION 
    / DRIVEBASE_ENCODER_ROTATIONS_PER_WHEEL_ROTATION);
    frontLeft.getEncoder().setVelocityConversionFactor(DRIVEBASE_METERS_PER_WHEEL_ROTATION 
    / DRIVEBASE_ENCODER_ROTATIONS_PER_WHEEL_ROTATION / 60);

    backRight.restoreFactoryDefaults();
    backLeft.restoreFactoryDefaults();
    frontRight.setIdleMode(IdleMode.kCoast);
    frontLeft.setIdleMode(IdleMode.kCoast);
    frontLeft.setInverted(true);
    backLeft.setInverted(true);
    backRight.follow(frontRight, false);
    backLeft.follow(frontLeft, false);

    burnFlash();


    SmartDashboard.putBoolean("requestPoseReset", false);

    if (RobotBase.isSimulation()) {
      m_driveSim = new DifferentialDrivetrainSim(
        DRIVEBASE_PLANT,
        DRIVEBASE_GEARBOX,
        1 / DRIVEBASE_ENCODER_ROTATIONS_PER_WHEEL_ROTATION,
        DRIVEBASE_TRACKWIDTH,
        Units.inchesToMeters(6), // wheel radius is half of an encoder position unit.
        DRIVEBASE_SIM_ENCODER_STD_DEV);
		}
    resetRobotPose(START_POSE);
    
  }


  /**
   * Save the motor controller settings in their onboard permanent memory, in case of momentary power loss.
   */
  public void burnFlash() {
    frontRight.burnFlash();
    frontLeft.burnFlash();
    backRight.burnFlash();
    backLeft.burnFlash();
  }

  /**
   * Creates a deadband for the drivebase joystick
   */
  public double deadbandJoysticks(double value) {
    if (Math.abs(value) < DRIVEBASE_DEADBAND) {
      value = 0;
    }
    return value;
  }
  

  /**
   * Gets the gyro heading as reported by the sensor. 
   * 
   * This is 0 on robot power-up, even if the robot is not actually pointed downfield.
   * The odometry handles offsetting this value to match the pose the odometry has been reset to.
   * @return the gyro heading as a Rotation2d
   */
  @Log(methodName = "getRadians", name = "gyroHeading")
  public Rotation2d getGyroHeading() {
      return navX.getRotation2d();
  }

  /**
   * Get the rotation as estimated by the odometry.
   * @return
   */
  @Log(methodName = "getRadians")
  public Rotation2d getEstimatedHeading() {
    return odometry.getPoseMeters().getRotation();
  }

  /**
   * Curvature drive method
   * 
   * This drives the robot in smooth arcs, treating the turning axis similarly to a
   * car steering wheel. When the robot is stopped in the fwd/back direction, turning switches to turn-in-place.
   * Forward back is from 1 to -1 (forward positive), turn is from 1 to -1 (clockwise/arc to the right positive)
   */
  public void curvatureDrive(double fwdBack, double turn) {
    fwdBack = MathUtil.applyDeadband(fwdBack, 0.02);
    turn = MathUtil.applyDeadband(turn, 0.05);
    fwdBack = fwdBackLimiter.calculate(fwdBack);
    turn = turnLimiter.calculate(turn);
    boolean quickTurn = false;
    if (fwdBack == 0) {
      quickTurn = true;
      turn *= 0.35;
    }
    else {
      turn *= 0.8;
    }
    WheelSpeeds speeds = DifferentialDrive.curvatureDriveIK(fwdBack, turn, quickTurn);
    tankDrive(speeds.left, speeds.right);
  }

  /**
   * makes robot drive with both wheels going in the same direction. 
   * 
   * Scales the -1 to 1 parameters to some fraction of a maximum provided voltage.
   * 
   * @param left
   * @param right
   */
  public void tankDrive(double left, double right) {
    SmartDashboard.putNumber("leftSpeed", left);
    SmartDashboard.putNumber("rightSpeed", right);
    frontLeft.setVoltage(left * 6.5);
    frontRight.setVoltage(right * 6.5);
  }

  /**
   * Uses FF and PID to drive with a specified velocity in meters per second for each side.
   * @param leftVelocityMPS
   * @param rightVelocityMPS
   */
  public void tankDriveVelocity(double leftVelocityMPS, double rightVelocityMPS) {
    SmartDashboard.putNumber("leftVelo", leftVelocityMPS);
    SmartDashboard.putNumber("rightVelo", rightVelocityMPS);
    tankDriveVolts(
      leftFF.calculate(leftVelocityMPS) + leftPID.calculate(getLeftVelocity(), leftVelocityMPS),
      rightFF.calculate(rightVelocityMPS) + rightPID.calculate(getRightVelocity(), rightVelocityMPS));
  }

  /**
   * Sets motor speed to 0 when subsystem ends
   */
  public void stopAll() {
    tankDriveVelocity(0, 0);
  }

  @Override
  public void periodic() {
    // This method will be called once per scheduler run
    // Update odometry with the gyro heading and encoder positions.
    if (RobotBase.isReal()) {
      odometry.update(getGyroHeading(), 
      frontLeft.getEncoder().getPosition(),
      frontRight.getEncoder().getPosition()
      );
    }
    else {
      odometry.update(getGyroHeading(), m_leftEncoder.getPosition(), m_rightEncoder.getPosition());
    }
  }

  @Override
  public void simulationPeriodic() {
		// Set the inputs to the system, which are the applied voltages on the leader motor controllers.
		m_driveSim.setInputs(frontLeft.getAppliedOutput(), frontRight.getAppliedOutput());

		// Advance the model by 20 ms.
		m_driveSim.update(0.02);

		// Update all of our sensors.
		m_leftEncoder.setPosition(m_driveSim.getLeftPositionMeters());
		m_leftEncoder.setVelocity(m_driveSim.getLeftVelocityMetersPerSecond());
		m_rightEncoder.setPosition(m_driveSim.getRightPositionMeters());
		m_rightEncoder.setVelocity(m_driveSim.getRightVelocityMetersPerSecond());
    setSimGyro(m_driveSim.getHeading().getRadians());
	}

  /**
   * Get the current wheel velocities as a DifferentialDriveWheelSpeeds object, which stores
   * left and right velocities in meters per second. This is used in the Ramsete Controller.
   * @return
   */
  public DifferentialDriveWheelSpeeds getWheelSpeeds() {
    return new DifferentialDriveWheelSpeeds(getLeftVelocity(), getRightVelocity());
  }

  /**
   * Get the robot movement as a ChassisSpeeds object. X is forward meters/sec, Y is sideways m/s
   * (left positive, but this is 0 on differential drive), and omega is radians/sec, counterclockwise positive
   * @return
   */
  public ChassisSpeeds getChassisSpeeds() {
    return DRIVEBASE_KINEMATICS.toChassisSpeeds(getWheelSpeeds());
  }

  /**
   * Gets the left velocity of the drivebase in meters per second.
   * @return
   */
  @Log
  public double getLeftVelocity() {
    if(RobotBase.isReal()) {
      return frontLeft.getEncoder().getVelocity();
    }
    else {
      return m_leftEncoder.getVelocity();
    }
  }

  /**
   * Gets the right velocity of the drivebase in meters per second.
   * @return
   */
  @Log
  public double getRightVelocity() {
    if(RobotBase.isReal()) {
      return frontRight.getEncoder().getVelocity();
    }
    else {
      return m_rightEncoder.getVelocity();
    }
  }

  /**
   * Resets both encoders to 0. This MUST be done when resetting the odometry pose.
   */
  public void resetEncoders() {
    if(RobotBase.isReal())
    {
      frontLeft.getEncoder().setPosition(0);
      frontRight.getEncoder().setPosition(0);
    }
    else {
      m_leftEncoder.setPosition(0);
      m_leftEncoder.setVelocity(0);
      m_rightEncoder.setPosition(0);
      m_rightEncoder.setVelocity(0);
    }


  }
  
  /**
   * Gets the robot pose in field-relative coordinates.
   * @return
   */
  @Log(methodName = "toString")
  public Pose2d getRobotPose() {
    return odometry.getPoseMeters();
  }

  /**
   * Resets odometry to the specified pose. Also resets encoders to 0, but does not modify the gyro heading.
   * @param pose
   */
  public void resetRobotPose(Pose2d pose) {
    odometry.resetPosition(pose, getGyroHeading());
    resetEncoders(); 
       
  }

  /**
   * Resets the robot pose to x=0, y=0, rot=0
   */
  public void resetRobotPose() {
    resetRobotPose(new Pose2d());
  }

  public void tankDriveVolts(double leftVolts, double rightVolts) {
    if(RobotBase.isSimulation()) {
        leftVolts = NomadMathUtil.subtractkS(leftVolts, Constants.DRIVEBASE_LINEAR_FF_MPS[0]);
        rightVolts = NomadMathUtil.subtractkS(rightVolts, Constants.DRIVEBASE_LINEAR_FF_MPS[0]);
    }
    frontLeft.setVoltage(leftVolts);
    frontRight.setVoltage(rightVolts);
  }

  /**
   * Sets brake/coast mode on all controllers.
   * @param mode
   */
  public void setIdleState(IdleMode mode) {
    frontLeft.setIdleMode(mode);
    frontRight.setIdleMode(mode);
    backLeft.setIdleMode(mode);
    backRight.setIdleMode(mode);
  }

  /**
   * Sets the yaw of the navX in sim.
   * 
   * @param newState The radians value of the sim heading.
   * 
   */
  public void setSimGyro(double newState) {
    int dev = SimDeviceDataJNI.getSimDeviceHandle("navX-Sensor[0]");
    SimDouble gyroSimAngle = new SimDouble(SimDeviceDataJNI.getSimValueHandle(dev, "Yaw"));
    gyroSimAngle.set(-Units.radiansToDegrees(newState));
  }

  /*COMMANDS*/

    /**
   * Creates a curvature drive command that does not naturally end.
   * 
   * @param fwdBack    the forward/back speed [-1..1]
   * @param turn       the turn tightness [-1..1]
   * @param drivebaseS the drivebase subsystem
   * @return the CurvatureDriveC.
   */
  public Command createCurvatureDriveC(DoubleSupplier fwdBack, DoubleSupplier turn) {
    return new RunEndCommand(
        () -> {
          this.curvatureDrive(
              fwdBack.getAsDouble(),
              turn.getAsDouble());
        },
        this::stopAll,
        this)
            .withName("CurvatureDriveC");
  }


  public Command timedDriveC(double power, double time) {
    return new RunEndCommand(
      () -> {
          this.tankDrive(power, power);
        },
        this::stopAll,
        this)
            .withTimeout(time)
            .withName("DriveTimedC");
  }

  public Command ramseteC(Trajectory trajectory) {
    return new RamseteCommand(
        trajectory,
        this::getRobotPose,
        this.ramseteController,
        Constants.DRIVEBASE_KINEMATICS,
        this::tankDriveVelocity,
        this
      ).andThen(stopC());
  }

  public Command stopC() {
    return new InstantCommand(this::stopAll, this);
  }

  /**
   * Resets the odometry to the pose supplied by the given PoseSupplier.
   * 
   * We use a Supplier because the starting pose may not be known until runtime.
   * @param poseSupplier A supplier for the new pose.
   * @return The InstantCommand that resets the odometry.
   */
  public Command resetOdometryC(Supplier<Pose2d> poseSupplier) {
    return new InstantCommand(()->resetRobotPose(poseSupplier.get()));
  }
}
