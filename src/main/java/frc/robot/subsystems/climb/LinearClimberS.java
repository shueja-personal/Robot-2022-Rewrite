// Copyright (c) FIRST and other WPILib contributors.
// Open Source Software; you can modify and/or share it under the terms of
// the WPILib BSD license file in the root directory of this project.

package frc.robot.subsystems.climb;

import com.revrobotics.CANSparkMax;
import com.revrobotics.CANSparkMax.IdleMode;
import com.revrobotics.CANSparkMax.SoftLimitDirection;
import com.revrobotics.CANSparkMaxLowLevel.MotorType;
import com.revrobotics.RelativeEncoder;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.InstantCommand;
import edu.wpi.first.wpilibj2.command.RunCommand;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import frc.robot.Constants;
import frc.robot.util.command.RunEndCommand;
import io.github.oblarg.oblog.Loggable;
import io.github.oblarg.oblog.annotations.Log;

public class LinearClimberS extends SubsystemBase implements Loggable {
  private CANSparkMax frontSparkMax = new CANSparkMax(Constants.CAN_ID_CLIMBER_MOTOR, MotorType.kBrushless);
  @Log(methodName = "getPosition", name = "frontPosition")
  private RelativeEncoder sparkMaxEncoder = frontSparkMax.getEncoder();

  /** Creates a new ClimberS. */
  public LinearClimberS() {
    
    frontSparkMax.restoreFactoryDefaults();
    frontSparkMax.enableSoftLimit(SoftLimitDirection.kForward, true);
    frontSparkMax.enableSoftLimit(SoftLimitDirection.kReverse, true);
    frontSparkMax.setSoftLimit(SoftLimitDirection.kForward, (float) Constants.CLIMBER_FRONT_SOFT_LIMIT_FORWARD);
    frontSparkMax.setSoftLimit(SoftLimitDirection.kReverse, (float) Constants.CLIMBER_FRONT_SOFT_LIMIT_MID);
    frontSparkMax.setIdleMode(IdleMode.kBrake);
    frontSparkMax.setSmartCurrentLimit(40, 40, 0);
    frontSparkMax.burnFlash();
  }

  public double getFrontPosition() {
    return sparkMaxEncoder.getPosition();
  }

  public void driveFront(double voltage) {
    frontSparkMax.setVoltage(voltage);
  }

  public void transferFront() {
    driveFront(Constants.CLIMBER_FRONT_TRANSFER_VOLTS);
  }
  
  public void extendFront() {
    driveFront(10);
  }

  public void retractFront() {
    driveFront(-7.5);
  }

  public void stopFront() {
    driveFront(0);
  }

  public void holdFront() {
    driveFront(0.5);
  }

  @Override
  public void periodic() {
  }
}
