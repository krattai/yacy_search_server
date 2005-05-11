// transferURL.java 
// -----------------------
// part of the AnomicHTTPD caching proxy
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004, 2005
// last change: 24.01.2005
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notive above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.

// you must compile this file with
// javac -classpath .:../classes transferRWI.java


import de.anomic.http.httpHeader;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;
import de.anomic.yacy.yacyCore;

public class transferURL {

    public static serverObjects respond(httpHeader header, serverObjects post, serverSwitch env) {
	// return variable that accumulates replacements
        plasmaSwitchboard switchboard = (plasmaSwitchboard) env;
	serverObjects prop = new serverObjects();
        
	if ((post == null) || (env == null)) return prop;

	// request values
	String iam      = (String) post.get("iam", "");      // seed hash of requester
        String youare   = (String) post.get("youare", "");   // seed hash of the target peer, needed for network stability
	String key      = (String) post.get("key", "");      // transmission key
	int urlc        = Integer.parseInt((String) post.get("urlc", ""));    // number of transported urls
        boolean granted = switchboard.getConfig("allowReceiveIndex", "false").equals("true");
	
        // response values
        String result = "";
        String doublevalues = "0";
        
        if (granted) {
            int received = 0;
            int sizeBefore = switchboard.loadedURL.size();
            // read the urls from the other properties and store
            String urls;
            for (int i = 0; i < urlc; i++) {
                urls = (String) post.get("url" + i);
                if (urls != null) {
                    switchboard.loadedURL.newEntry(urls, true, iam, iam, 3);
                    received++;
                }
            }
            
            yacyCore.seedDB.mySeed.incRU(received);
            
            // return rewrite properties
            int more = switchboard.loadedURL.size() - sizeBefore;
            doublevalues = "" + (received - more);
            switchboard.getLog().logInfo("Received " + received + " URL's from peer " + iam);
            if ((received - more) > 0) switchboard.getLog().logError("Received " + doublevalues + " double URL's from peer " + iam);
            result = "ok";
        } else {
            result = "error_not_granted";
        }
        
        prop.put("double", doublevalues);
        prop.put("result", result);
	return prop;
    }

}
