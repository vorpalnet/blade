
var aliceUtils =
{
	selectFile: function( editor )
	{
		
		aliceEditor = editor;
		
		
	    var xhttp = new XMLHttpRequest();
	    xhttp.open("GET", "../resources/microservices", false); 
	    xhttp.setRequestHeader("Content-type", "application/xhtml+xml");
	    xhttp.send(null);
		
			var div = document.createElement('div');
			var p = document.createElement('p');
		    
		    p.innerHTML = xhttp.responseText;
			
			div.appendChild(p);
			
			var w = document.body.clientWidth;
			var h = Math.max(document.body.clientHeight || 0, document.documentElement.clientHeight)
			
			fileListWindow = new mxWindow('Select File', div, w/2-50, h/2-100, 100, 200, false, true);

			fileListWindow.setClosable(true);
			fileListWindow.setVisible(true);
		
	},
	
	
	loadFile: function( link )
	{	
		fileListWindow.destroy();
		var filename = "../resources/microservices/"+link.text;
		
		try
		{
			aliceEditor.open(filename);
		}
		catch (e)
		{
		  mxUtils.error('Cannot open ' + filename + ': ' + e.message, 280, true);
		}
		
		
	},
	
	

};
