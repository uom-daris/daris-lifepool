package vicnode.daris.lifepool;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Vector;

import nig.mf.client.util.AssetUtil;
import nig.mf.client.util.ClientConnection;
import nig.mf.pssd.ProjectRole;
import nig.util.DateUtil;
import arc.mf.client.ServerClient.Connection;
import arc.xml.XmlDoc;
import arc.xml.XmlStringWriter;

public class ParseManifest {

	// See https://docs.google.com/document/d/1skiNkR8lxx_cW9pCW2OEsZn30wYrkLr8n4LR1OgUmMU/edit?usp=sharing

	// Number of columns in CSV file
	private static int NCOLUMNS = 11;

	public static final String HELP_ARG = "-help";
	public static final String CID_ARG = "-cid";
	public static final String PATH_ARG = "-path";
	public static final String KEEPDICOM_ARG = "-keep";
	public static final String DATE_ARG = "-count";           // Days before link expires
	public static final String DEBUG_ARG = "-debug";
	public static final String DESTROY_ARG = "-destroy";

	// This string must match argument "app" when generating the secure identity token
	private static final String TOKEN_APP = "LifePool-Parser";

	// These are the DICOM tags for the elements of interest in the correct order (the same order
	// as the columns of the manifest file)
	private static final String[] DICOM_ELEMENT_TAGS={"00080050", "00080008", "00080060", "00080068", "00080070", "00080080",
		"0008103E", "00081090", "00181400", "00185101", "00200062"};
	private static final String[] DICOM_ELEMENT_NAMES = {"Accession Number", "ImageType", "Modality", "Presentation Intent Type", 
		"Manufacturer", "Institution", "Series Description", "Model", "Acquisition Device Processing Description", 
		"View Position", "Image Laterality"};



	private static class Options {

		public String  path = null;
		public String cid = null;
		public Boolean keep = false;
		public String dateCount = "14";
		public String date = null;
		public Boolean debug = false;
		public Boolean destroy = false;
		//
		public void print () {
			System.out.println("ParseManifest Parameters");
			System.out.println("path               = " + path);
			System.out.println("CID                = " + cid);
			System.out.println("                   = " + keep);
			System.out.println("Date Count (days)  = " + dateCount + " which is " + date);
			System.out.println("debug              = " + debug);
			System.out.println("destroy (token)    = " + destroy);
			System.out.println("");
		}
	}


	public static void main(String[] args) throws Throwable {

		// Parse inputs
		// Parse user inputs
		Options ops = new Options();
		for (int i = 0; i < args.length; i++) {
			if (args[i].equalsIgnoreCase(HELP_ARG)) {
				printUsage();
				System.exit(0);
			} else if (args[i].equalsIgnoreCase(PATH_ARG)) {
				ops.path = args[++i];
			} else if (args[i].equalsIgnoreCase(CID_ARG)) {
				ops.cid = args[++i];
			} else if (args[i].equalsIgnoreCase(DATE_ARG)) {
				ops.dateCount = args[++i];
			} else if (args[i].equalsIgnoreCase(KEEPDICOM_ARG)) {
				ops.keep = true;
			} else if (args[i].equalsIgnoreCase(DEBUG_ARG)) {
				ops.debug = true;
			} else if (args[i].equalsIgnoreCase(DESTROY_ARG)) {
				ops.destroy = true;
			} else {
				ops.print();
				System.err.println("ParseManifest: error: unexpected argument = '" + args[i] + "'");
				printUsage();
				System.exit(1);	 
			}
		}
		if (ops.cid==null) {
			printUsage();
			throw new Exception("You must supply the argument " + CID_ARG);
		}
		if (ops.path==null) {
			printUsage();
			throw new Exception("You must supply the argument " + PATH_ARG);
		}

		// Convert date count (days) to date
		convertDate (ops);

		// Print out arguments
		ops.print();

		// Open and read CSV file
		File file = new File(ops.path);
		List<String> rows = readFile(file);

		// Validate format
		validateManifest (rows);

		//
		if (ops.debug) {
			System.out.println("*** CSV File contents:");
			for (String row : rows) {
				System.out.println("row=" + row);
			}
			System.out.println("");
		}

		// Open connection to server using domain/user or token using system properties passed in
		// from wrapper script (e.g. mf.port). 
		Connection cxn = ClientConnection.createServerConnection();
		ClientConnection.connect(cxn, TOKEN_APP, false);


		// Produce list of filtered asset IDs
		System.out.println("\n*** Find and filter data");
		List<String> assetIDs = filterManifest(cxn, rows, ops);

		System.out.println("\n*** Found " + assetIDs.size() + " filtered DataSets");
		if (ops.debug && assetIDs.size()>0) {
			System.out.println("   Data Set IDs");
			for (String assetID : assetIDs) {
				System.out.println("   id,cid = " +assetID + ", " + idToCid(cxn,assetID));
			}
		}


		// Generate the shareable link. Caller must have authority to do so.
		if (assetIDs.size()>0) {
			generateShareableLink (cxn, assetIDs, ops);
		}

		// CLose connection to server
		cxn.close();
	}


	private static  void convertDate (Options ops) throws Throwable {
		Date m = new Date();
		Calendar cal = Calendar.getInstance();  
		cal.setTime(m);  
		cal.add(Calendar.DATE, Integer.parseInt(ops.dateCount));
		m = cal.getTime();   
		ops.date = DateUtil.formatDate(m, "dd-MMM-yyyy");
	}


	private static void printUsage() {

		System.out.println("\n Usage: parse-manifest -help -cid <DaRIS Project CID>  -path <Manifest CSV file path> -count <days> -keep -debug -destroy \n");
		System.out.println("   -cid     : The citeable ID of the LifePool DaRIS Project");
		System.out.println("   -path    : The full path to the CSV manifest file");
		System.out.println("   -count   : The number of days that the shareable link should remain active for (default 14).");
		System.out.println("   -keep    : A parameter has been set - if the corresponding DICOM element is null don't consider this parameter for filtering (so keep the DataSet).");
		System.out.println("              The default behaviour is that the DataSet is dropped when the DICOM element is null.\n");
		System.out.println("   -debug   : Turn on  extra printing.");
	} 

	private static List<String> filterManifest (Connection cxn, List<String> rows, Options ops) throws Throwable {

		// Cam has suggested that one accession ID may spread over several consecutive rows and the operators
		// would be lined by an OR.  An alternative would be to allow fields per parameters to combine
		// operators.  =="X" | == "Y" (probably easier to handle since we can stick with one row)

		int i = 0;
		if (ops.debug) System.out.println("*** There are " + rows.size() + " rows to process in the manifest file");

		// Initialize filtered list of DataSets
		List<String> assetIDs = new ArrayList<String>();

		// Iterate through manifest rows.  Each row is tokenized (each token is an operator
		// and parameter <op><param>).  We find the DataSets that match the accession number. Then 
		// we filter those based on the tokens and the DICOM meta-data held on the DataSets
		for (String row : rows) {
			if (ops.debug) System.out.println("*** Processing row = " + row);
			if (i==0) {
				// Drop Header row
			} else {

				// Tokenize the row
				Vector<String> tokens= parseRow(row, ops);
				if (tokens.size()!=NCOLUMNS) {
					throw new Exception("Parsed row " + row + " into only " + tokens.size() + " tokens instead of " + NCOLUMNS);
				}
				System.out.println("   Accession Number " + tokens.get(0));
				if (ops.debug) {
					System.out.println("tokens length=" + tokens.size());
					for (int j=0; j<tokens.size();j++) {
						System.out.println ("'" + tokens.get(j) +"' ");
					}
				}

				// Filter DataSets for this row.
				Collection<String> assets = findAndFilter (cxn, ops.cid, tokens,  ops);
				System.out.println("      Found " + assets.size() + " DataSets after filtering");
				if (assets!=null) {
					assetIDs.addAll(assets);
				} 
			}
			i++;
		}

		return assetIDs;
	}



	private static Vector<String> parseRow(String row, Options ops) throws Throwable {

		// The current algorithm seaches for values between " " and then works 
		// back to find the operator.  It might be more robust to 
		// search for the next operator patterm (== and !=) and then
		// work forwards to find the "<value>".  This will probably be easier
		// to handle multiple operator values (when we enhance) i.e.
		// , =="ABC"||!="XYZ", =="LEFT" and be more robust to white space.
		// At the moment we only handle , =="ABC", =="XYZ", ...

		// <Accession number>,<op>"<value>",<op>"<value>",....
		// <op>"<value>",<op><value>,....
		// 
		Vector<String> tokens = new Vector<String>();
		int n = row.length();
		if (ops.debug) {
			System.out.println("length="+n);
		}

		// Fetch the Accession Number which is first with no operator
		int idx = row.indexOf(",");
		String t = row.substring(0, idx);
		tokens.add(removeEquals(t));
		if (ops.debug) {
			System.out.println("Accession number="+tokens.get(0));
		}

		// Now the rest 
		boolean more = true;
		// start indexes into row
		int start = idx;
		//

		while (more) {		

			// Next section of row
			t = row.substring(start);
			if (ops.debug) {
				System.out.println("next section='"+t + "'");
			}

			// Is the next char a "," ? If so, this token is missing
			if (t.substring(0,2).equals(",,")) {
				tokens.add(null);
				if (ops.debug) {
					System.out.println("Extracted null token");
				}
				start += 1;
			} else {

				// OUr next section will look like e.g.
				// ,=="DERIVED\PRIMARY\LEFT",=="FOR PRESENTATION",=="MG",=="SIEMENS" ...
				// ,<op><"<par>"

				// FInd start "
				int firstEq = t.indexOf("\"");

				// FInd end "
				int lastEq = t.indexOf("\"", firstEq+1);
				if (ops.debug) {
					System.out.println("local first,last="+firstEq + " " + lastEq);
				}

				// Make these indices index into row rather than a substring
				firstEq += start;
				lastEq += start;
				if (ops.debug) {
					System.out.println("global first,last="+firstEq + " " + lastEq);
				}


				// Extract the token being the <op> and the <par> without ""
				String token = row.substring(firstEq-2,firstEq) + row.substring(firstEq+1,lastEq);
				if (ops.debug) {
					System.out.println("Extracted token = '"+ token.trim() + "'");
				}
				tokens.add(token.trim());

				//
				start = lastEq + 1;
			}
			if (start>n-1) more = false;
		}
		return tokens;

	}

	/**
	 * Remove leading and trailing " from string
	 * @param t
	 * @return
	 * @throws Throwable
	 */

	private static String removeEquals (String t) throws Throwable {

		// Java substring(i1,i2) gives string from i1 to i2-1

		int n = t.length();
		int start = 0;
		if (t.substring(0,1).equals("\"")) {
			start = 1;
		}

		//
		int end = n;
		if (t.substring(n-1,n).equals("\"")) {
			end = n-1;		
		}
		return t.substring(start,end);		
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
	private static Collection<String> findAndFilter (Connection cxn, String pid, Vector<String> tokens, Options ops) throws Throwable {	

		// Output list
		ArrayList<String> keepIDs = new ArrayList<String>();

		// Accession NUmber
		String accessionNo = tokens.get(0);

		// FInd assets with the given accession ID
		XmlStringWriter w = new XmlStringWriter();
		String where = "cid starts with '" + pid + "' and model='om.pssd.dataset' and ";
		String tag = DICOM_ELEMENT_TAGS[0];
		where += "xpath(daris:dicom-dataset/object/de[@tag='"+tag+"']/value)='" + accessionNo + "'";			
		w.add("where", where);
		XmlDoc.Element r = cxn.execute("asset.query", w.document());
		Collection<String> ids = r.values("id");
		if (ids==null) return null;
		if (ops.debug) System.out.println("         Found " + ids.size() + " DataSets to filter");

		// Now filter based on the imaging parameters
		for (String id : ids) {
			if (ops.debug) System.out.println("            Processing DataSet " + idToCid(cxn, id));

			// Fetch the asset meta-data
			XmlDoc.Element asset = AssetUtil.getMeta(cxn, id, null);
			if (asset!=null) {
				// Work through the expected parameter types. If the parameter token is null, then
				// that means we don't use it to test with. If the parameter is provided, but the
				// DICOM element is null, then we drop the DataSet (as we can't make the test).
				// There is an option (-keep) to change this algorithm so that if a parameter
				// is specified, but the DICOM meta-data are null, then don't consider that
				// parameter (keep the DataSet).
				//
				// There may be multiple parameter values (e.g. ImageType).  All of them must
				// be satisfied by DICOM values for the DataSet to be kept.

				Boolean keep = true;
				for (int i=1;i<DICOM_ELEMENT_TAGS.length; i++) {   // 0 already consumed with Accession Number
					String t = tokens.get(i);
					if (t!=null) {
						if (!testToken (asset, DICOM_ELEMENT_TAGS[i], t, ops)) {
							keep = false;
							break;
						}
					} else {
						if (ops.debug) {
							System.out.println("         For DICOM element name " + DICOM_ELEMENT_NAMES[i] + " tokens["+i+"] is null");
						}
					}
				}

				// Add the DataSet to the kept list if appropriate
				if (keep) keepIDs.add(id);
			}
		}
		//
		if (ops.debug) System.out.println("         Found " + keepIDs.size() + " DataSets after filtering");
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
	private static Boolean testToken (XmlDoc.Element asset, String tag, String token, Options ops) throws Throwable {

		// Fetch the relevant DICOM element.
		XmlDoc.Element dicomElement = asset.element("asset/meta/daris:dicom-dataset/object/de[@tag='"+tag+"']");

		if (dicomElement==null) {
			if (ops.keep) {
				// A parameter is set - if the DICOM element is null don't consider this parameter for filtering (so keep the DataSet)
				return true;
			} else {
				// A parameter is set -  If the DICOM element is null drop the DataSet (default algorithm)
				return false;
			}
		}

		// See if the token contains multiple values to be compared against those in the
		// DICOM header.  Multiple values are separated by \
		String op = token.substring(0,2);
		String val = token.substring(2);
		String[] vals = val.split("\\\\");	     // It's a lot to get just one \ !
		if (ops.debug) {
			System.out.println("      Parsed " + val + " into " + vals.length + " values");
		}


		// Get the DICOM value(s)
		Collection<String> dicomValues = dicomElement.values("value");   // May be multiples...	

		// All parameter values must be found in the DICOM.
		for (int i=0; i<vals.length; i++) {
			if (ops.debug) {
				System.out.println("     Testing parameter value " + vals[i]);
			}
			Boolean found = false;
			for (String dicomValue : dicomValues) {	
				if (ops.debug) {
					System.out.println("        Testing against DICOM value " + dicomValue);
				}
				// Excluded meaningless DICOM values
				if (!(dicomValue.isEmpty()) && !(dicomValue.length()==0) && !dicomValue.equals(" ")) {
					if (parseStringTokenForOneDICOMValue (vals[i], op, dicomValue)) {
						found = true;
						break;
					}
				} else {
					if (ops.debug) {
						System.out.println("     DICOM value is meaningless (null/empty)");

					}
				}
			}

			// We could not find this parameter value in the DICOM values so bug out.
			if (!found) return false;
		}
		return true;
	}






	private static Boolean parseStringTokenForOneDICOMValue (String parVal, String op, String dicomValue) throws Throwable {
		if (op.equals("==")) {
			return parVal.equals(dicomValue);
		} else if (op.equals("!=")) {
			return !(parVal.equals(dicomValue));
		} else {
			throw new Exception ("Can't parse parameter '" + op + parVal + "'");
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
			throw new Exception ("Error parsing CSV file, wrong number of columns (" + tokens.length + ") - should be " + NCOLUMNS);
		}
		int n = rows.size() - 1;
		System.out.println("*** There are " + n + " image rows to process");

		// I could also make sure all the column names are correct, but I don't currently
		// use them. The columns are assumed in the correct order.
	}

	private static String generateShareableLink (Connection cxn, List<String> ids, Options ops) throws Throwable {

		// Generate a read-only role to access the collection
		String  guestRole =  ProjectRole.guestRoleNameOf(ops.cid);    	

		// Now create a secure identity token with access to the data
		XmlStringWriter w = new XmlStringWriter();
		w.add("role", new String[]{"type", "role"}, "daris:pssd.model.user");        // daris framework
		w.add("role", new String[]{"type", "role"}, "daris:pssd.subject.create");    // daris framework
		w.add("role", new String[] {"type", "role" }, "vicnode.daris:pssd.model.user");  // vicnode.daris package
		w.add("role", new String[] {"type", "role" }, guestRole);                   // LifePool project
		w.add("role", new String[] {"type", "role"}, "user");                       // ACL on root namespace on VicNode system
		w.push("service", new String[] { "name", "daris.collection.archive.create" });
		w.add("cid", ops.cid);
		String where = "";

		// FOr a lot of IDs this would be cumbersome
		// TBD: write a downloadable app
		int i = 0;
		for (String id : ids) {
			if (i==0) {
				where = "id="+id;
			} else {
				where += " or id="+id;
			}
			i++;
		}
		w.add("where", where);
		//
		w.add("parts", "all");
		w.add("include-attachments", false);
		w.add("decompress", true);
		w.add("format", "zip");
		w.pop();
		w.add("grant-caller-transient-roles", false);

		// Bound usage of token 
		if (ops.date!=null) {
			w.add("to", ops.date);              // End date of use
		}
		w.add("tag", "daris-manifest-url-");

		// Limit token size
		w.add("max-token-length", 20);
		w.add("min-token-length", 20);
		XmlDoc.Element r = cxn.execute("secure.identity.token.create", w.document());
		XmlDoc.Element token = r.element("token");
		String actorID = token.value("@actor-name");
		if (ops.debug) System.out.println("*** The actor ID for the secure token is " + actorID);
		if (ops.destroy) {
			w = new XmlStringWriter();
			w.add ("token", token.value());
			cxn.execute("secure.identity.token.destroy", w.document());
			System.out.println("*** Destroyed token with actor id " + actorID);
		}

		// Now generate a shareable link. Refetch server parameters.
		String protocol = getProperty("mf.transport");
		String host = getProperty("mf.host");
		String port = getProperty("mf.port");
		String filename = ops.cid+".zip";
		String url = protocol + "://" + host + ":" + port + "/mflux/execute.mfjp?token=" + token.value() + "&filename="+filename;
		System.out.println("\n\n\n*** The shareable link (expiry " + ops.date + ") is \n\n " + url + "\n");
		//
		return url;
	}


	public static String getProperty (String property) throws Throwable {
		String host = System.getProperty(property);
		if (host == null) {
			throw new Exception("Cannot find system property '" + property + "'");
		}
		return host;
	}

}
