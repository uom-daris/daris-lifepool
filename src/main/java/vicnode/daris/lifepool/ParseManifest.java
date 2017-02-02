package vicnode.daris.lifepool;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import nig.mf.client.util.AssetUtil;
import nig.mf.client.util.ClientConnection;
import arc.mf.client.ServerClient.Connection;
import arc.xml.XmlDoc;
import arc.xml.XmlStringWriter;

public class ParseManifest {

	// Number of columns in CSV file
	private static int NCOLUMNS = 11;

	public static void main(String[] args) throws Throwable {

		// Parse inputs
		if (args == null || args.length != 2) {
			printUsage();
			System.exit(1);
		}
		String cid = args[0];
		String path = args[1];

		// Open and read CSV file
		File file = new File(path);
		List<String> rows = readFile(file);

		//
		System.out.println("CSV File contents:");
		for (String row : rows) {
			System.out.println("row=" + row);
		}

		// Open connection to server
		// Make connection to MF server 	
		Connection cxn = ClientConnection.createServerConnection();
		ClientConnection.connect(cxn, false);

		// Produce list of filtered asset IDs
		System.out.println("Find and filter");
		List<String> assetIDs = filterManifest(cxn, rows, cid);
		System.out.println("Found filtered assets");
		for (String assetID : assetIDs) {
			System.out.println("id,cid = " +assetID + ", " + idToCid(cxn,assetID));
		}

		// CLose connection to server
		cxn.close();
	}


	private static void printUsage() {

		System.out.println("Usage: <DaRIS Project CID> <Manifest CSV file path>");
	} 

	private static List<String> filterManifest (Connection cxn, List<String> rows, String pid) throws Throwable {

		// Cam has suggested that one accession ID may spread over several consecutive rows and the operators
		// would be lined by an OR.  An alternative would be to allow fields per parameters to combine
		// operators.  =="X" | == "Y" (probably easier to handle since we can stick with one row)

		int i = 0;
		System.out.println("There are " + rows.size() + " rows to process");
		List<String> assetIDs = new ArrayList<String>();
		
		// Iterate through manifest rows.  Each row is tokenized.  We find the assets
		// that match the accession number. Then we filter those based on the tokens
		// and the DICOM meta-data held on the datasets.
		for (String row : rows) {
			System.out.println("Processing row = " + row);
			if (i==0) {
				System.out.println("   dropped [headers]");

				// Headers
				i++;
			} else {

				// Tokenize the row
				String[] tokens = row.split(",");
				if (tokens.length != NCOLUMNS) {
					throw new Exception ("Error parsing CSV file, wrong number of columns - should be " + NCOLUMNS);
				}
				
				// Trim off white space
				for (int j=0; j< tokens.length; j++) {
					tokens[j] = tokens[j].trim();
				}

				// TBD find columns via column header names instead of assuming order.
				// Extract tokens (<op><value>)
				// If tokens are empty make them null for clarity
				// A parameter token should be <operator><parameter value>
				// where the operators are == and !=

				// Accession Number
				String accessionNo = tokens[0];

				// Image Type (0008,0008)
				String imageType = extractToken(tokens[1]);

				// DICOM modality (0008,0060)
				String modality = extractToken(tokens[2]);

				// Presentation Intent Type (0008,0068)
				String presentationIntentType =  extractToken(tokens[3]);

				// Manufacturer (0008,0070)
				String manufacturer= extractToken(tokens[4]);

				// Institution (0008,0080)
				String institution = extractToken(tokens[5]);

				// Series Description (0008,103E)
				String seriesDesc = extractToken(tokens[6]);

				// Model Name (0008,1090)
				String model = extractToken(tokens[7]);

				// Acquisition Device Processing Description (0018,1400)
				String acqDevProcDesc = extractToken(tokens[8]);

				// View Position (0018,5101)
				String viewPosition = extractToken(tokens[9]);

				// Image Laterality (0020,0062)
				String imageLat = extractToken(tokens[10]);

				System.out.println("      Find assets for Accession No. " + accessionNo);
				Collection<String> assets = findAndFilter (cxn, pid, accessionNo, modality, imageType, presentationIntentType,
						manufacturer, institution, seriesDesc, model, acqDevProcDesc, viewPosition, imageLat);
				if (assets!=null) {
					assetIDs.addAll(assets);
				}
			}
		}

		return assetIDs;
	}
	
	
	private static String extractToken (String item) throws Throwable {
		
		// Fetch
		String token = item;
		if (token.isEmpty() || token.length()==0) token = null;
		return token;
	}


	/**
	 * For the current row of the manifest, find and filter the DataSets
	 * 
	 * @param cxn
	 * @param pid
	 * @param accessionNo
	 * @param modality
	 * @param imageType
	 * @param presentationIntentType
	 * @param manufacturer
	 * @param institution
	 * @param seriesDesc
	 * @param model
	 * @param acqDevProcDesc
	 * @param viewPosition
	 * @param imageLat
	 * @return
	 * @throws Throwable
	 */
	private static Collection<String> findAndFilter (Connection cxn, String pid, String accessionNo, 
			String modality, String imageType, String presentationIntentType, String manufacturer,
			String institution, String seriesDesc, String model, String acqDevProcDesc, 
			String viewPosition, String imageLat) throws Throwable {
		// Output list
		ArrayList<String> keepIDs = new ArrayList<String>();

		// FInd assets with the given accession ID
		XmlStringWriter w = new XmlStringWriter();
		String where = "cid starts with '" + pid + "' and model='om.pssd.dataset' and ";
		where += "xpath(daris:dicom-dataset/object/de[@tag='00080050']/value)='" + accessionNo + "'";			
		w.add("where", where);
		XmlDoc.Element r = cxn.execute("asset.query", w.document());
		Collection<String> ids = r.values("id");
		if (ids==null) return null;
		System.out.println("      Found " + ids.size() + " assets");

		// Now filter based on the imaging parameters
		for (String id : ids) {
			System.out.println("      Processing asset " + id);

			// Fetch the asset meta-data
			XmlDoc.Element asset = AssetUtil.getMeta(cxn, id, null);
			if (asset!=null) {

				// Get the bit of metadata that we want
				XmlDoc.Element meta = asset.element ("asset/meta/daris:dicom-dataset");
				if (meta!=null) {

					// FInd all the extracted DICOM elements
					Collection<XmlDoc.Element> dicomElements = meta.elements("object/de");
					if (dicomElements!=null) {

						// The logic is that a DataSet is kept if the AND of all parameter ops is true
						// Possibly flip the algorithm so if a parameter is specified but the DICOM element
						// is missing, then don't keep the DataSet. At the moment, if the DICOM element
						// is missing, its just not considered in the filtering.
						Boolean keep = true;
						System.out.println("      Iterating over DICOM " + dicomElements.size()  + " elements");
						for (XmlDoc.Element dicomElement : dicomElements) {

							// What element is this ?
							String tag = dicomElement.value("@tag");
							Collection<String> dicomValues = dicomElement.values("value");   // May be multiples...

							// Compare the token and DICOM value
							if (tag.equals("00080050")) {
								// Accession Number
								// Nothing to do
							} else if (tag.equals("00080008")) {
								// Image Type
								if (!parseStringToken (imageType, dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00080060")) {
								// Modality
								if (!parseStringToken (modality, dicomValues)) {
									keep = false;
									break;
								}					
							} else if (tag.equals("00080068")) {
								// Presentation Intent Type
								if (!parseStringToken (presentationIntentType, dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00080070")) {
								// Manufacturer
								if (!parseStringToken (manufacturer, dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00080080")) {
								// Institution Name
								if (!parseStringToken (institution, dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("0008103E")) {
								// Series Description
								if (!parseStringToken (seriesDesc, dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00081090")) {
								// Manufacturers model
								if (!parseStringToken (model, dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00181400")) {
								// Acquisition Device Processing Description
								if (!parseStringToken (acqDevProcDesc, dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00185101")) {
								// View Position
								if (!parseStringToken (viewPosition, dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00200062")) {
								// Image Laterality
								if (!parseStringToken (imageLat, dicomValues)) {
									keep = false;
									break;
								}
							}
						}
						if (keep) {
							System.out.println("      Keeping this DataSet");
							// Add asset to list
							keepIDs.add(id);
						} else {
							System.out.println("      Not Keeping this DataSet");

						}
					}
				}
			}
		}

		//
		return keepIDs;
	}


	/**
	 * The parameter consists of <op><value> where <op> can be == or !=
	 * TBD We may allow multi op parameters in the future (so they would be ORed)
	 *    <op><value> <op><value> ....
	 * 
	 * @param parameter
	 * @param dicomValue
	 * @return
	 * @throws Throwable
	 */
	private static Boolean parseStringToken (String parameter, Collection<String> dicomValues) throws Throwable {

		
		// If a parameter field is empty, then that means we don't want to use that
		// parameter for testing whether the DataSet should be kept or not.  So
		// therefore we keep it by default.
		if (parameter==null) return true;
	
		// We OR the results for multiple DICOM values
		Boolean keep = false;
		for (String dicomValue : dicomValues) {	
			
			// Filter out meaningless DICOM values
			if (!(dicomValue.isEmpty()) && !(dicomValue.length()==0) && !dicomValue.equals(" ")) {
				System.out.println("         Parsing parameter = " + parameter + " and DICOM value = " + dicomValue);
				if (parseStringTokenForOneDICOMValue (parameter, dicomValue)) keep = true;
			}
		}
		return keep;
	}


	private static Boolean parseStringTokenForOneDICOMValue (String parameter, String dicomValue) throws Throwable {
		// Parse the parameter : <op><value> where <op> = '==' or '!='
		String op = parameter.substring(0,2);
		String val = parameter.substring(2);
		if (op.equals("==")) {
			return val.equals(dicomValue);
		} else if (op.equals("!=")) {
			return !(val.equals(dicomValue));
		} else {
			throw new Exception ("Can't parse parameter '" + parameter +"'");
		}
	}


	private static List<String> readFile (File fin) throws Throwable  {
		ArrayList<String> list = new ArrayList<String>();
		// Construct BufferedReader from FileReader
		BufferedReader br = new BufferedReader(new FileReader(fin));

		String line = null;
		while ((line = br.readLine()) != null) {
			list.add(line);
		}

		br.close();
		return list;
	}


	/**
	 * Convert asset ID to asset CID (if it has one)
	 * 
	 * @param executor
	 * @param id
	 * @return
	 * @throws Throwable
	 */
	public static String idToCid(Connection cxn, String id) throws Throwable {

		XmlStringWriter dm = new XmlStringWriter("args");
		dm.add("id", id);
		dm.add("pdist", 0); // Force local
		XmlDoc.Element r = cxn.execute("asset.get", dm.document());
		return r.value("asset/cid");
	}

}