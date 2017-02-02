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
		if (args == null) { 
			printUsage();
			System.exit(1);
		}
		if (args.length!=2 && args.length != 3) {
			printUsage();
		}
		//
		String cid = args[0];
		String path = args[1];
		Boolean dropIfDICOMNull = true;
		if (args.length==3) {
		   dropIfDICOMNull = false;
		}
		System.out.println("Project ID = " + cid);
		System.out.println("Path = " + path);
		System.out.println("Keep If DICOM null = " + !dropIfDICOMNull);

		// Open and read CSV file
		File file = new File(path);
		//		List<String> rows = readFile(file);
		List<String> rows = fakeLaptopData();


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
		List<String> assetIDs = filterManifest(cxn, rows, cid, dropIfDICOMNull);
		System.out.println("Found " + assetIDs.size() + " filtered DataSets");
		for (String assetID : assetIDs) {
			System.out.println("id,cid = " +assetID + ", " + idToCid(cxn,assetID));
		}

		// CLose connection to server
		cxn.close();
	}


	private static List<String> fakeVicNodeData () {
		ArrayList<String> rows = new ArrayList<String>();
		rows.add("Accession ID, Image Type, Modality, Presentation Intent Type, Manufacturer, Institution, Series Description, Model Name, Acquisition Device Processing Description, View Position, Image Laterality");
		rows.add("0000098SM1001, ==ORIGINAL, ==MG, ==FOR PRESENTATION,  ==Sectra Imtec AB, ==BreastScreen Victoria 218, ==ZZZ, ==L30, ==ABC, ==CC, ==L");
		rows.add("0000054SM1203, ==DERIVED, ==MG, ==FOR PRESENTATION,  ==FUJIFILM Corporation, ==BREASTSCREEN Berwick, ==L CC, ==L30, ==L CC, ==L CC, ==L");
		return rows;
	}

	private static List<String> fakeLaptopData () {
		ArrayList<String> rows = new ArrayList<String>();
		rows.add("Accession ID, Image Type, Modality, Presentation Intent Type, Manufacturer, Institution, Series Description, Model Name, Acquisition Device Processing Description, View Position, Image Laterality");
		rows.add("0000071SM1101, ==DERIVED, ==MG, ==FOR PRESENTATION,  ==FUJIFILM Corporation, ==MDI FRANKSTONE, ==LEFT BREAST CC,, ==LEFT BREAST CC,, ==L");
		return rows;
	}



	private static void printUsage() {

		System.out.println("Usage: <DaRIS Project CID> <Manifest CSV file path> <keepDicomIfNull>");
	} 

	private static List<String> filterManifest (Connection cxn, List<String> rows, String pid, Boolean dropIfDICOMNull) throws Throwable {

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
				Collection<String> assets = findAndFilter (cxn, pid, tokens, dropIfDICOMNull);
				if (assets!=null) {
					assetIDs.addAll(assets);
				}
			}
			i++;
		}

		return assetIDs;
	}






	/**
	 * For the current row of the manifest, find and filter the DataSets. In this Method we drop
	 * a DataSet if a parameter is non-null but its correposnding DICOM element is null.
	 * 
	 * @param cxn
	 * @param pid
	 * @param tokens
	 * @return
	 * @throws Throwable
	 */
	private static Collection<String> findAndFilter (Connection cxn, String pid, String[] tokens, Boolean dropIfDICOMNull) throws Throwable {	

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
				// Work through the expected parameter types. If the parameter token is null, then
				// that means we don't use it to test with. If the parameter is provided, but the
				// DICOM element is null, then we drop the DataSet (as we can't make the test).
				// If there are multiple DICOM values, satisfying ant of them will cause the
				// DataSet to be accepted (we OR them)

				// ImageType 
				if (tokens[1]!=null) {
					if (!testToken (asset, "00080008", tokens[1], dropIfDICOMNull)) break;
				}

				// DICOM modality
				if (tokens[2]!=null) {
					if (!testToken (asset, "00080060", tokens[2], dropIfDICOMNull)) break;
				}

				// Presentation Intent Type 
				if (tokens[3]!=null) {
					if (!testToken (asset, "00080068", tokens[3], dropIfDICOMNull)) break;
				}

				// Manufacturer
				if (tokens[4]!=null) {
					if (!testToken (asset, "00080070", tokens[4], dropIfDICOMNull)) break;
				}

				// Institution 
				if (tokens[5]!=null) {
					if (!testToken (asset, "00080080", tokens[5], dropIfDICOMNull)) break;
				}

				// Series Description
				if (tokens[6]!=null) {
					if (!testToken (asset, "0008103E", tokens[6], dropIfDICOMNull)) break;
				}

				// Model
				if (tokens[7]!=null) {
					if (!testToken (asset, "00081090", tokens[7], dropIfDICOMNull)) break;
				}

				// Acquisition Device Processing Description
				if (tokens[8]!=null) {
					if (!testToken (asset, "00181400", tokens[8], dropIfDICOMNull)) break;
				}

				// View Position
				if (tokens[9]!=null) {
					if (!testToken (asset, "00185101", tokens[9], dropIfDICOMNull)) break;
				}

				// Image Laterality
				if (tokens[10]!=null) {
					if (!testToken (asset, "00200062", tokens[10], dropIfDICOMNull)) break;
				}


				// If we get here, we keep the DataSet
				keepIDs.add(id);
			}
		}

		//
		return keepIDs;
	}


	/**
	 * 
	 * @param asset
	 * @param tag
	 * @param token
	 * @param method
	 * @return
	 * @throws Throwable
	 */
	private static Boolean testToken (XmlDoc.Element asset, String tag, String token, Boolean dropIfDICOMNull) throws Throwable {

		// Fetch the relevant DICOM element. If it is null, we can't test with it for this token
		// parameter, so we can't keep the DataSet
		XmlDoc.Element dicomElement = asset.element("asset/meta/daris:dicom-dataset/object/de[@tag='"+tag+"']");

		if (dicomElement==null) {
			if (dropIfDICOMNull) {
				// A parameter is set - If the DICOM element is null drop the DataSet
				return false;
			} else {
				// A parameter is set - If the DICOM element is don't consider this parameter for filtering (so keep it)
				return true;
			}
		}

		// Get the value(s)
		Collection<String> dicomValues = dicomElement.values("value");   // May be multiples...	

		// We OR the results for multiple DICOM values
		Boolean keep = false;
		for (String dicomValue : dicomValues) {	
			if (!(dicomValue.isEmpty()) && !(dicomValue.length()==0) && !dicomValue.equals(" ")) {
				if (parseStringTokenForOneDICOMValue (token, dicomValue)) keep = true;
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