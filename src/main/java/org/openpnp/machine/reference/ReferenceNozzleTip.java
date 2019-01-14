package org.openpnp.machine.reference;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JOptionPane;

import org.apache.commons.io.IOUtils;
import org.opencv.core.KeyPoint;
import org.opencv.core.RotatedRect;
import org.openpnp.ConfigurationListener;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.wizards.ReferenceNozzleTipConfigurationWizard;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;
import org.openpnp.spi.PropertySheetHolder;
import org.openpnp.spi.base.AbstractNozzleTip;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.OpenCvUtils;
import org.openpnp.util.UiUtils;
import org.openpnp.util.VisionUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.CvStage.Result;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;

public class ReferenceNozzleTip extends AbstractNozzleTip {
    // TODO Remove after October 1, 2017.
    @Element(required = false)
    private Double changerStartSpeed = null;
    @Element(required = false)
    private Double changerMidSpeed = null;
    @Element(required = false)
    private Double changerMidSpeed2 = null;
    @Element(required = false)
    private Double changerEndSpeed = null;
    // END TODO Remove after October 1, 2017.
    
    @ElementList(required = false, entry = "id")
    private Set<String> compatiblePackageIds = new HashSet<>();

    @Attribute(required = false)
    private boolean allowIncompatiblePackages;
    
    @Attribute(required = false)
    private int pickDwellMilliseconds;

    @Attribute(required = false)
    private int placeDwellMilliseconds;

    @Element(required = false)
    private Location changerStartLocation = new Location(LengthUnit.Millimeters);

    @Element(required = false)
    private double changerStartToMidSpeed = 1D;
    
    @Element(required = false)
    private Location changerMidLocation = new Location(LengthUnit.Millimeters);
    
    @Element(required = false)
    private double changerMidToMid2Speed = 1D;
    
    @Element(required = false)
    private Location changerMidLocation2;
    
    @Element(required = false)
    private double changerMid2ToEndSpeed = 1D;
    
    @Element(required = false)
    private Location changerEndLocation = new Location(LengthUnit.Millimeters);
    
    
    @Element(required = false)
    private Calibration calibration = new Calibration();


    @Element(required = false)
    private double vacuumLevelPartOn;

    @Element(required = false)
    private double vacuumLevelPartOff;
    
    private Set<org.openpnp.model.Package> compatiblePackages = new HashSet<>();

    public ReferenceNozzleTip() {
        Configuration.get().addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationLoaded(Configuration configuration) throws Exception {
                for (String id : compatiblePackageIds) {
                    org.openpnp.model.Package pkg = configuration.getPackage(id);
                    if (pkg == null) {
                        continue;
                    }
                    compatiblePackages.add(pkg);
                }
                /*
                 * Backwards compatibility. Since this field is being added after the fact, if
                 * the field is not specified in the config then we just make a copy of the
                 * other mid location. The result is that if a user already has a changer
                 * configured they will not suddenly have a move to 0,0,0,0 which would break
                 * everything.
                 */
                if (changerMidLocation2 == null) {
                    changerMidLocation2 = changerMidLocation.derive(null, null, null, null);
                }
                /*
                 * Backwards compatibility for speed settings.
                 *  Map the old variables to new one if present in machine.xlm and null the old ones
                 *  */
                if (changerStartSpeed != null) {
                 changerStartToMidSpeed = changerStartSpeed;
                 changerStartSpeed = null;
            	}
                if (changerMidSpeed != null) {
                	changerMidToMid2Speed = changerMidSpeed;
                	changerMidSpeed = null;
                }
                if (changerMidSpeed2 !=null) {
                	changerMid2ToEndSpeed = changerMidSpeed2;
                	changerMidSpeed2 = null;
                }
                if (changerEndSpeed != null) {
                	changerEndSpeed = null;
                }
            }
        });
    }

    @Override
    public boolean canHandle(Part part) {
        boolean result =
                allowIncompatiblePackages || compatiblePackages.contains(part.getPackage());
        // Logger.debug("{}.canHandle({}) => {}", getName(), part.getId(), result);
        return result;
    }

    public Set<org.openpnp.model.Package> getCompatiblePackages() {
        return new HashSet<>(compatiblePackages);
    }

    public void setCompatiblePackages(Set<org.openpnp.model.Package> compatiblePackages) {
        this.compatiblePackages.clear();
        this.compatiblePackages.addAll(compatiblePackages);
        compatiblePackageIds.clear();
        for (org.openpnp.model.Package pkg : compatiblePackages) {
            compatiblePackageIds.add(pkg.getId());
        }
    }

    @Override
    public String toString() {
        return getName() + " " + getId();
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new ReferenceNozzleTipConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName() + " " + getName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return new Action[] {unloadAction, loadAction, deleteAction};
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        return new PropertySheet[] {new PropertySheetWizardAdapter(getConfigurationWizard())};
    }

    public boolean isAllowIncompatiblePackages() {
        return allowIncompatiblePackages;
    }

    public void setAllowIncompatiblePackages(boolean allowIncompatiblePackages) {
        this.allowIncompatiblePackages = allowIncompatiblePackages;
    }
    
    public int getPickDwellMilliseconds() {
        return pickDwellMilliseconds;
    }

    public void setPickDwellMilliseconds(int pickDwellMilliseconds) {
        this.pickDwellMilliseconds = pickDwellMilliseconds;
    }

    public int getPlaceDwellMilliseconds() {
        return placeDwellMilliseconds;
    }

    public void setPlaceDwellMilliseconds(int placeDwellMilliseconds) {
        this.placeDwellMilliseconds = placeDwellMilliseconds;
    }

    public Location getChangerStartLocation() {
        return changerStartLocation;
    }

    public void setChangerStartLocation(Location changerStartLocation) {
        this.changerStartLocation = changerStartLocation;
    }

    public Location getChangerMidLocation() {
        return changerMidLocation;
    }

    public void setChangerMidLocation(Location changerMidLocation) {
        this.changerMidLocation = changerMidLocation;
    }

    public Location getChangerMidLocation2() {
        return changerMidLocation2;
    }

    public void setChangerMidLocation2(Location changerMidLocation2) {
        this.changerMidLocation2 = changerMidLocation2;
    }

    public Location getChangerEndLocation() {
        return changerEndLocation;
    }

    public void setChangerEndLocation(Location changerEndLocation) {
        this.changerEndLocation = changerEndLocation;
    }
    
    public double getChangerStartToMidSpeed() {
        return changerStartToMidSpeed;
    }

    public void setChangerStartToMidSpeed(double changerStartToMidSpeed) {
        this.changerStartToMidSpeed = changerStartToMidSpeed;
    }

    public double getChangerMidToMid2Speed() {
        return changerMidToMid2Speed;
    }

    public void setChangerMidToMid2Speed(double changerMidToMid2Speed) {
        this.changerMidToMid2Speed = changerMidToMid2Speed;
    }

    public double getChangerMid2ToEndSpeed() {
        return changerMid2ToEndSpeed;
    }

    public void setChangerMid2ToEndSpeed(double changerMid2ToEndSpeed) {
        this.changerMid2ToEndSpeed = changerMid2ToEndSpeed;
    }

    private Nozzle getParentNozzle() {
        for (Head head : Configuration.get().getMachine().getHeads()) {
            for (Nozzle nozzle : head.getNozzles()) {
                for (NozzleTip nozzleTip : nozzle.getNozzleTips()) {
                    if (nozzleTip == this) {
                        return nozzle;
                    }
                }
            }
        }
        return null;
    }
	
    public double getVacuumLevelPartOn() {
        return vacuumLevelPartOn;
    }

    public void setVacuumLevelPartOn(double vacuumLevelPartOn) {
        this.vacuumLevelPartOn = vacuumLevelPartOn;
    }

    public double getVacuumLevelPartOff() {
        return vacuumLevelPartOff;
    }

    public void setVacuumLevelPartOff(double vacuumLevelPartOff) {
        this.vacuumLevelPartOff = vacuumLevelPartOff;
    }

    public Calibration getCalibration() {
        return calibration;
    }

    public Action loadAction = new AbstractAction("Load") {
        {
            putValue(SMALL_ICON, Icons.nozzleTipLoad);
            putValue(NAME, "Load");
            putValue(SHORT_DESCRIPTION, "Load the currently selected nozzle tip.");
        }

        @Override
        public void actionPerformed(final ActionEvent arg0) {
            UiUtils.submitUiMachineTask(() -> {
                getParentNozzle().loadNozzleTip(ReferenceNozzleTip.this);
            });
        }
    };

    public Action unloadAction = new AbstractAction("Unload") {
        {
            putValue(SMALL_ICON, Icons.nozzleTipUnload);
            putValue(NAME, "Unload");
            putValue(SHORT_DESCRIPTION, "Unload the currently loaded nozzle tip.");
        }

        @Override
        public void actionPerformed(final ActionEvent arg0) {
            UiUtils.submitUiMachineTask(() -> {
                getParentNozzle().unloadNozzleTip();
            });
        }
    };
    
    public Action deleteAction = new AbstractAction("Delete Nozzle Tip") {
        {
            putValue(SMALL_ICON, Icons.nozzleTipRemove);
            putValue(NAME, "Delete Nozzle Tip");
            putValue(SHORT_DESCRIPTION, "Delete the currently selected nozzle tip.");
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            int ret = JOptionPane.showConfirmDialog(MainFrame.get(),
                    "Are you sure you want to delete " + getName() + "?",
                    "Delete " + getName() + "?", JOptionPane.YES_NO_OPTION);
            if (ret == JOptionPane.YES_OPTION) {
                getParentNozzle().removeNozzleTip(ReferenceNozzleTip.this);
            }
        }
    };

    @Root
    public static class Calibration {
        public static class Circle {
            final double centerX;
            final double centerY;
            final double radius;

            public Circle(double centerX, double centerY, double radius) {
                this.centerX = centerX;
                this.centerY = centerY;
                this.radius = radius;
            }

            @Override
            public String toString() {
                return "centerX: " + centerX + " centerY: " + centerY + " radius: " + radius;
            }
        }
        
        @Element(required = false)
        private CvPipeline pipeline = createDefaultPipeline();

        @Attribute(required = false)
        private double angleIncrement = 15;
        
        @Attribute(required = false)
        private boolean enabled;
        
        private boolean calibrating;
        
        private Circle nozzleEccentricity = null;
        double phaseShift;

        
        /* The reworked calibration routine will fit a circle into the runout nozzle path.
         * the center of the circle represents the rotational axis, the radius of the circle is the runout
         * so some refs to fit an circle function to xy-points sorted by easy to more complex:
         */
        public void calibrate(ReferenceNozzleTip nozzleTip) throws Exception {
        	/* TODO:
        	 * a) check whether it works correct for limited head movement (+-180°)
        	 * b) for 
        	 */
        	
            if (!isEnabled()) {
            	reset();
                return;
            }
            try {
                calibrating = true;
                
            	reset();

                Nozzle nozzle = nozzleTip.getParentNozzle();
                Camera camera = VisionUtils.getBottomVisionCamera();

                // Move to the camera with an angle of 0.
                Location cameraLocation = camera.getLocation();
                // This is our baseline location
                Location measureBaseLocation = cameraLocation.derive(null, null, null, 0d);
                
                // move nozzle to the camera location at zero degree - the nozzle must not necessarily be at the center
                MovableUtils.moveToLocationAtSafeZ(nozzle, measureBaseLocation);

                // Capture nozzle tip positions and add them to a list. For these calcs the camera location is considered to be 0/0
                List<Location> nozzleTipMeasuredLocations = new ArrayList<>();
                for (double measureAngle = 0; measureAngle < 360; measureAngle += angleIncrement) {	// hint: if nozzle is limited to +-180° this is respected in .moveTo automatically
                	// rotate nozzle to measurement angle
                    Location measureLocation = measureBaseLocation.derive(null, null, null, measureAngle);
                    nozzle.moveTo(measureLocation);
                    
                    // detect the nozzle tip
                    Location offset = findCircle();
                    offset = offset.derive(null, null, null, measureAngle);		// for later usage in the algorithm, the measureAngle is stored to the offset location 
                    
                    // add offset to array
                    nozzleTipMeasuredLocations.add(offset);
                    
                    //TODO: catch case, no location was found (currently error message shows just a "0") in pipeline and
                    // a) show an error or
                    // b) ignore that if there are still enough points (has to be checked afterwards) 
                }
                
                System.out.println("measured offsets: " + nozzleTipMeasuredLocations);
                
            	// the measured offsets describe a circle with the rotational axis as the center, the runout is the circle radius
                this.nozzleEccentricity = this.calcCircleFitKasa(nozzleTipMeasuredLocations);
                Logger.debug("calculated nozzleEccentricity: {}", this.nozzleEccentricity);
                
                // now calc the phase shift for angle mapping on moves
                this.phaseShift = this.calcPhaseShift(nozzleTipMeasuredLocations);
                Logger.debug("calculated phaseShift: {}", this.phaseShift);
                
                nozzle.moveToSafeZ();
            }
            finally {
                calibrating = false;
            }
        }
        
        public double calcPhaseShift(List<Location> nozzleTipMeasuredLocations) {
        	/*
        	 * The phaseShift is calculated to map the angle the nozzle is located mechanically at
        	 * (that is what openpnp shows in the DRO) to the angle, the nozzle tip is located wrt. to the
        	 * centered circle runout path.
        	 * With the phaseShift available, the calibration offset can be calculated analytically for every
        	 * location/rotation even if not captured while measured (stepped by angleIncrement)
        	 * 
        	 */
        	double phaseShift = 0;
        	
        	double angle=0;
        	double measuredAngle=0;
        	double differenceAngleMean=0;
        	

            Iterator<Location> nozzleTipMeasuredLocationsIterator = nozzleTipMeasuredLocations.iterator();
    		while (nozzleTipMeasuredLocationsIterator.hasNext()) {
    			Location measuredLocation = nozzleTipMeasuredLocationsIterator.next();
    			
    			// get the measurement rotation
        		angle = measuredLocation.getRotation();		// the angle at which the measurement was made was stored to the nozzleTipMeasuredLocation into the rotation attribute
        		
        		// move the offset-location by the centerY/centerY. by this all offset-locations are wrt. the 0/0 origin
        		Location centeredLocation = measuredLocation.subtract(new Location(LengthUnit.Millimeters,this.nozzleEccentricity.centerX,this.nozzleEccentricity.centerY,0.,0.));
        		
        		// calculate the angle, the nozzle tip is located at
        		measuredAngle=Math.toDegrees(Math.atan2(centeredLocation.getY(), centeredLocation.getX()));
        		
        		// the difference is the phaseShift
        		double differenceAngle = angle-measuredAngle;
        		
        		// atan2 outputs angles from -PI to +PI. If one wants positive values, one needs to add +PI to negative values
        		if(differenceAngle<0) differenceAngle += 360;
        		
        		System.out.println("differenceAngle " + differenceAngle);
        		
        		// sum up all differenceAngles to build the average later
        		differenceAngleMean += differenceAngle;
    		}
        	
    		// calc the average
        	phaseShift = differenceAngleMean / nozzleTipMeasuredLocations.size();
        	System.out.println("phaseShift " + phaseShift);
        	
        	return phaseShift;
        }
        
        public Circle calcCircleFitKasa(List<Location> nozzleTipMeasuredLocations) {
        	/* 
        	 * this function fits a circle my means of the Kasa Method to the given List<Location>.
        	 * this is a java port of http://people.cas.uab.edu/~mosya/cl/CPPcircle.html 
        	 * The Kasa method should work well for this purpose since the measured locations are captured along a full circle
        	 */
    		int n;

    	    double Xi,Yi,Zi;
    	    double Mxy,Mxx,Myy,Mxz,Myz;
    	    double B,C,G11,G12,G22,D1,D2;
    	    double meanX=0.0, meanY=0.0;
    	    
    	    Circle nozzleEccentricity = null;
    	    n = nozzleTipMeasuredLocations.size();

            Iterator<Location> nozzleTipMeasuredLocationsIterator = nozzleTipMeasuredLocations.iterator();
    		while (nozzleTipMeasuredLocationsIterator.hasNext()) {
    			Location measuredLocation = nozzleTipMeasuredLocationsIterator.next();
    			meanX += measuredLocation.getX();
    			meanY += measuredLocation.getY();
    		}
    		meanX = meanX / (double)nozzleTipMeasuredLocations.size();
    		meanY = meanY / (double)nozzleTipMeasuredLocations.size();
    		
    	    Mxx=Myy=Mxy=Mxz=Myz=0.;

    	    for (int i = 0; i < n; i++) {
    	        Xi = nozzleTipMeasuredLocations.get(i).getX() - meanX;   //  centered x-coordinates
    	        Yi = nozzleTipMeasuredLocations.get(i).getY() - meanY;   //  centered y-coordinates
    	        Zi = Xi*Xi + Yi*Yi;

    	        Mxx += Xi*Xi;
    	        Myy += Yi*Yi;
    	        Mxy += Xi*Yi;
    	        Mxz += Xi*Zi;
    	        Myz += Yi*Zi;
    	    }
    	    Mxx /= n;
    	    Myy /= n;
    	    Mxy /= n;
    	    Mxz /= n;
    	    Myz /= n;

	    	// solving system of equations by Cholesky factorization
    	    G11 = Math.sqrt(Mxx);
    	    G12 = Mxy / G11;
    	    G22 = Math.sqrt(Myy - G12 * G12);

    	    D1 = Mxz / G11;
    	    D2 = (Myz - D1*G12)/G22;

    	    // computing parameters of the fitting circle
    	    C = D2/G22/2.0;
    	    B = (D1 - G12*C)/G11/2.0;
    	    
    	    // assembling the output
    	    nozzleEccentricity = new Circle( B + meanX, C + meanY, Math.sqrt(B*B + C*C + Mxx + Myy));
            
    	    return nozzleEccentricity;
        }

        public Location getCalibratedOffset(double angle) {
        	//R+center -> from that the x-y-offsets can be recalculated at any angle...
    	    
            if (!isEnabled() || !isCalibrated()) {
                return new Location(LengthUnit.Millimeters, 0, 0, 0, 0);
            }

            // Make sure the angle is between 0 and 360.
            while (angle < 0) {
                angle += 360;
            }
            while (angle > 360) {
                angle -= 360;
            }
            
            //add phase shift
            angle = angle - this.phaseShift;
            
            angle = Math.toRadians(angle);
            
            /* convert from polar coords to xy cartesian offset values
             * https://blog.demofox.org/2013/10/12/converting-to-and-from-polar-spherical-coordinates-made-easy/
             */
            double offsetX = nozzleEccentricity.centerX + (nozzleEccentricity.radius * Math.cos(angle));
            double offsetY = nozzleEccentricity.centerY + (nozzleEccentricity.radius * Math.sin(angle));
        	
            /*
             * thought: the eccentricity-values (centerX/Y) are the offset to the downlooking cam and can be an hint for
             * a) the nozzle-head-offset not 100% correct(?)
             * b) ...?
             * should that values be included in correction or make these things worse?
             */
            
            return new Location(LengthUnit.Millimeters, offsetX, offsetY, 0, 0);
        }

        private Location findCircle() throws Exception {
            Camera camera = VisionUtils.getBottomVisionCamera();
            try (CvPipeline pipeline = getPipeline()) {
                pipeline.setProperty("camera", camera);
                pipeline.process();
                Location location;
                
                Object result = pipeline.getResult(VisionUtils.PIPELINE_RESULTS_NAME).model;
                if (result instanceof List) {
                    if (((List) result).get(0) instanceof Result.Circle) {
                    	Result.Circle circle = ((List<Result.Circle>) result).get(0);
                    	//TODO: has this list to be sorted? best match should be taken. but what is the best match? in best case, the pipeline returns always just one result
                    	//in every case: one can't use the shortest distance to the camera center any more, since that will not be the best match
                        location = VisionUtils.getPixelCenterOffsets(camera, circle.x, circle.y);
                        
                    }
                    else if (((List) result).get(0) instanceof KeyPoint) {
                        KeyPoint keyPoint = ((List<KeyPoint>) result).get(0);
                        location = VisionUtils.getPixelCenterOffsets(camera, keyPoint.pt.x, keyPoint.pt.y);
                    }
                    else {
                        throw new Exception("Unrecognized result " + result);
                    }
                }
                else if (result instanceof RotatedRect) {
                    RotatedRect rect = (RotatedRect) result;
                    location = VisionUtils.getPixelCenterOffsets(camera, rect.center.x, rect.center.y);
                }
                else {
                    throw new Exception("Unrecognized result " + result);
                }
                MainFrame.get().get().getCameraViews().getCameraView(camera).showFilteredImage(
                        OpenCvUtils.toBufferedImage(pipeline.getWorkingImage()), 250);
                return location;
            }
        }

        public static CvPipeline createDefaultPipeline() {
            try {
                String xml = IOUtils.toString(ReferenceNozzleTip.class
                        .getResource("ReferenceNozzleTip-Calibration-DefaultPipeline.xml"));
                return new CvPipeline(xml);
            }
            catch (Exception e) {
                throw new Error(e);
            }
        }

        public void reset() {
        	nozzleEccentricity = null;
        }

        public boolean isCalibrated() {
            return nozzleEccentricity != null;
        }
        
        public boolean isCalibrating() {
            return calibrating;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public boolean isCalibrationNeeded() {
            return isEnabled() && !isCalibrated() && !isCalibrating();
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public CvPipeline getPipeline() throws Exception {
            pipeline.setProperty("camera", VisionUtils.getBottomVisionCamera());
            return pipeline;
        }

        public void setPipeline(CvPipeline calibrationPipeline) {
            this.pipeline = calibrationPipeline;
        }
    }
}
