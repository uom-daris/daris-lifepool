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

		// Validate format
		validateManifest (rows);

		//
		System.out.println("CSV File contents:");
		for (String row : rows) {
			System.out.println("row=" + row);
		}

		// Open connection to server
		Connection cxn = ClientConnection.createServerConnection();
		ClientConnection.connect(cxn, false);

		// Produce list of filtered asset IDs
		System.out.println("Find and filter");
		List<String> assetIDs = filterManifest(cxn, rows, cid);
		System.out.println("Found " + assetIDs.size() + " filtered DataSets");
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
		System.out.println("There are " + rows.size() + " rows to process in the manifest file");

		// Initialize filtered list of DataSets
		List<String> assetIDs = new ArrayList<String>();

		// Iterate through manifest rows.  Each row is tokenized (each token is an operator
		// and parameter <op><param>).  We find the DataSets that match the accession number. Then 
		// we filter those based on the tokens and the DICOM meta-data held on the DataSets
		for (String row : rows) {
			System.out.println("Processing row = " + row);
			if (i==0) {
				// Drop Header row
			} else {

				// Tokenize the row
				String[] tokens = row.split(",");

				// Trim off white space
				for (int j=0; j< tokens.length; j++) {
					
					// If tokens are missing for a particular column set them to null for ease of use
					if (tokens[j].isEmpty() || tokens[j].length()==0) {
						if (j==0) {
							throw new Exception ("The Accession Number is missing for row " + i + " of the manifest file: " + row);
						}
						tokens[j] = null;
					} else {
						
						// Trim off white space
						tokens[j] = tokens[j].trim();
					}
				}

				// Filter DataSets for this row.
				System.out.println("      Find assets for Accession No. " + tokens[0] + " and filter");
				Collection<String> assets = findAndFilter (cxn, pid, tokens);
				if (assets!=null) {
					assetIDs.addAll(assets);
				}
			}
			i++;
		}

		return assetIDs;
	}






	/**
	 * For the current row of the manifest, find and filter the DataSets
	 * 
	 * @param cxn
	 * @param pid
	 * @param tokens
	 * @return
	 * @throws Throwable
	 */
	private static Collection<String> findAndFilter (Connection cxn, String pid, String[] tokens) throws Throwable {	
		
		// Output list
		ArrayList<String> keepIDs = new ArrayList<String>();
		
		// Accession NUmber
		String accessionNo = tokens[0];

		// FInd assets with the given accession ID
		XmlStringWriter w = new XmlStringWriter();
		String where = "cid starts with '" + pid + "' and model='om.pssd.dataset' and ";
		where += "xpath(daris:dicom-dataset/object/de[@tag='00080050']/value)='" + accessionNo + "'";			
		w.add("where", where);
		XmlDoc.Element r = cxn.execute("asset.query", w.document());
		Collection<String> ids = r.values("id");
		if (ids==null) return null;
		System.out.println("      Found " + ids.size() + " DataSets");

		// Now filter based on the imaging parameters
		for (String id : ids) {
			System.out.println("      Processing DataSet " + idToCid(cxn, id));

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
							
							// Tokens[] are:
							// 0 Accession Number
							// 1 Image Type (0008,0008)
							// 2 DICOM modality (0008,0060)
							// 3 Presentation Intent Type (0008,0068)
							// 4 Manufacturer (0008,0070)
							// 5 Institution (0008,0080)
							// 6 Series Description (0008,103E)
							// 7 Model Name (0008,1090)
							// 8 Acquisition Device Processing Description (0018,1400)
							// 9 View Position (0018,5101)
							// 10 Image Laterality (0020,0062)			
							
							
							// Compare the token and DICOM value
							if (tag.equals("00080008")) {
								// Image Type
								if (!parseStringToken (tokens[1], dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00080060")) {
								// Modality
								if (!parseStringToken (tokens[2], dicomValues)) {
									keep = false;
									break;
								}					
							} else if (tag.equals("00080068")) {
								// Presentation Intent Type
								if (!parseStringToken (tokens[3], dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00080070")) {
								// Manufacturer
								if (!parseStringToken (tokens[4], dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00080080")) {
								// Institution Name
								if (!parseStringToken (tokens[5], dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("0008103E")) {
								// Series Description
								if (!parseStringToken (tokens[6], dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00081090")) {
								// Manufacturers model
								if (!parseStringToken (tokens[7], dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00181400")) {
								// Acquisition Device Processing Description
								if (!parseStringToken (tokens[8], dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00185101")) {
								// View Position
								if (!parseStringToken (tokens[9], dicomValues)) {
									keep = false;
									break;
								}
							} else if (tag.equals("00200062")) {
								// Image Laterality
								if (!parseStringToken (tokens[10], dicomValues)) {
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



	private static void validateManifest (List<String> rows) throws Throwable {
		// Tokenize the first row
		String row = rows.get(0);
		String[] tokens = row.split(",");
		if (tokens.length != NCOLUMNS) {
			throw new Exception ("Error parsing CSV file, wrong number of columns - should be " + NCOLUMNS);
		}

		// I could also make sure all the column names are correct, but I don't currently
		// use them. The columns are assumed in the correct order.

	}

}