package psx;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import ghidra.app.services.AbstractAnalyzer;
import ghidra.app.services.AnalyzerType;
import ghidra.app.util.importer.MessageLog;
import ghidra.framework.Application;
import ghidra.framework.options.Options;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressRange;
import ghidra.program.model.address.AddressRangeIterator;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.util.task.TaskMonitor;
import psyq.SigApplier;


public class PsxAnalyzer extends AbstractAnalyzer {
	private Map<String, SigApplier> appliers;
	
	public static boolean onlyFirst = true;
	public static float minEntropy = 3.0f;
	private String manualVer = "4.7";
	
	private final String FIRST_OPTION = "Only first match";
	private final String MIN_ENTROPY = "Minimal signature entropy";
	private final String MANUAL_VER_OPTION = "PsyQ Version if not found";
	
	public static boolean isPsxLoader(Program program) {
		return program.getExecutableFormat().equalsIgnoreCase(PsxLoader.PSX_LOADER);
	}
	
	public PsxAnalyzer() {
		super("PsyQ Signatures", "PSX signatures applier", AnalyzerType.INSTRUCTION_ANALYZER);
		
		setSupportsOneTimeAnalysis();
		
		appliers = new HashMap<>();
	}
	
	@Override
	public boolean getDefaultEnablement(Program program) {
		return isPsxLoader(program);
	}

	@Override
	public boolean canAnalyze(Program program) {
		return isPsxLoader(program);
	}
	
	@Override
	public void registerOptions(Options options, Program program) {
		options.registerOption(FIRST_OPTION, onlyFirst, null, "To increase signatures applying time set this option CHECKED. Applies only first entry!");
		options.registerOption(MIN_ENTROPY, minEntropy, null, "To reduce false positive signatures applying set this value >= 3.0!");
		options.registerOption(MANUAL_VER_OPTION, manualVer, null, "Use this version number if not found in the binary.");
	}
	
	@Override
	public void optionsChanged(Options options, Program program) {
		super.optionsChanged(options, program);

		onlyFirst = options.getBoolean(FIRST_OPTION, onlyFirst);
		minEntropy = options.getFloat(MIN_ENTROPY, minEntropy);
		manualVer = options.getString(MANUAL_VER_OPTION, manualVer);
		
		appliers = new HashMap<>();
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log) {
		try {
			String psyqVersion = PsxLoader.getProgramPsyqVersion(program);
			
			AddressRangeIterator i = set.getAddressRanges();
			
			while (i.hasNext()) {
				AddressRange next = i.next();

				if (psyqVersion.isEmpty() && !manualVer.isEmpty()) {
					psyqVersion = manualVer.replace(".", "");
					PsxLoader.setProgramPsyqVersion(program, psyqVersion);
				}
				
				applyPsyqSignaturesByVersion(psyqVersion, program, next.getMinAddress(), next.getMaxAddress(), monitor, log);
			}
			
			monitor.setMessage("Applying PsyQ functions and data types...");
			monitor.clearCanceled();
			
			PsxLoader.loadPsyqGdt(program, set);
			
			monitor.setMessage("Applying PsyQ functions and data types done.");
		} catch (IOException e) {
			e.printStackTrace();
			log.appendException(e);
			return false;
		}
		
		return true;
	}
	
	private void applyPsyqSignaturesByVersion(final String version, Program program, final Address startAddr, final Address endAddr, TaskMonitor monitor, MessageLog log) throws IOException {
		final File patchesFile = Application.getModuleDataFile("psyq/patches.json").getFile(false);
		final String psyDir = String.format("psyq/%s", version);
		final File verDir = Application.getModuleDataSubDirectory(psyDir).getFile(false);
		
		File [] files = verDir.listFiles(new FilenameFilter() {
		    @Override
		    public boolean accept(File dir, String name) {
		        return name.endsWith(".json") && !name.equals("patches.json");
		    }
		});
		
		for (var file : files) {
			final String fileName = file.getName();
			SigApplier sig;
			
			if (appliers.containsKey(fileName)) {
				sig = appliers.get(fileName);
			} else {
				sig = new SigApplier(program.getName(), file.getAbsolutePath(), patchesFile.getAbsolutePath(), onlyFirst, minEntropy, monitor);
				appliers.put(fileName, sig);
			}
			
			sig.applySignatures(program, startAddr, endAddr, monitor, log);
		}
	}
}
