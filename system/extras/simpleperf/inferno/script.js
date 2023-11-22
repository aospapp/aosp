<script type="text/ecmascript">
function init() {
  var x = document.getElementsByTagName("svg")
  for (i = 0; i < x.length; i=i+1) {
      createZoomHistoryStack(x[i]);
  }
}

// Create a stack add the root svg element in it.
function createZoomHistoryStack(svgElement) {
  stack = [];
  svgElement.zoomStack = stack;
  stack.push(svgElement.getElementById(svgElement.attributes["rootid"].value))
}

function dumpStack(svgElement) {
  // Disable (enable for debugging)
  return
  stack = svgElement.zoomStack;
  for (i=0 ; i < stack.length; i++) {
    title = stack[i].getElementsByTagName("title")[0];
    console.log("[" +i+ "]-" + title.textContent)
  }
}

function adjust_node_text_size(x) {
  title = x.getElementsByTagName("title")[0];
  text = x.getElementsByTagName("text")[0];
  rect = x.getElementsByTagName("rect")[0];

  width = parseFloat(rect.attributes["width"].value);

  // Don't even bother trying to find a best fit. The area is too small.
  if (width < 25) {
      text.textContent = "";
      return;
  }
  // Remove dso and #samples which are here only for mouseover purposes.
  methodName = title.textContent.substring(0, title.textContent.indexOf("|"));

  var numCharacters;
  for (numCharacters=methodName.length; numCharacters>4; numCharacters--) {
     // Avoid reflow by using hard-coded estimate instead of text.getSubStringLength(0, numCharacters)
     // if (text.getSubStringLength(0, numCharacters) <= width) {
     if (numCharacters * 7.5 <= width) {
       break ;
     }
  }

  if (numCharacters == methodName.length) {
    text.textContent = methodName;
    return
  }

  text.textContent = methodName.substring(0, numCharacters-2) + "..";
 }

function adjust_text_size(svgElement) {
  var x = svgElement.getElementsByTagName("g");
  var i;
  for (i=0 ; i < x.length ; i=i+1) {
    adjust_node_text_size(x[i])
  }
}

function zoom(e) {
  svgElement = e.ownerSVGElement
  zoomStack = svgElement.zoomStack;
  zoomStack.push(e);
  displayFromElement(e)
  select(e);
  dumpStack(e.ownerSVGElement);

  // Show zoom out button.
  svgElement.getElementById("zoom_rect").style.display = "block";
  svgElement.getElementById("zoom_text").style.display = "block";
}

function displayFromElement(e) {
  var clicked_rect = e.getElementsByTagName("rect")[0];
  var clicked_origin_x = clicked_rect.attributes["ox"].value;
  var clicked_origin_y = clicked_rect.attributes["oy"].value;
  var clicked_origin_width = clicked_rect.attributes["owidth"].value;


  var svgBox = e.ownerSVGElement.getBoundingClientRect();
  var svgBoxHeight = svgBox.height
  var svgBoxWidth = svgBox.width
  var scaleFactor = svgBoxWidth/clicked_origin_width;

  var callsites = e.ownerSVGElement.getElementsByTagName("g");
  var i;
  for (i = 0; i < callsites.length; i=i+1) {
    text = callsites[i].getElementsByTagName("text")[0];
    rect = callsites[i].getElementsByTagName("rect")[0];

    rect_o_x = rect.attributes["ox"].value
    rect_o_y = parseFloat(rect.attributes["oy"].value)

    // Avoid multiple forced reflow by hiding nodes.
    if (rect_o_y > clicked_origin_y) {
     rect.style.display = "none"
     text.style.display = "none"
     continue;
    } else {
     rect.style.display = "block"
     text.style.display = "block"
    }

    rect.attributes["x"].value = newrec_x = (rect_o_x - clicked_origin_x) * scaleFactor ;
    rect.attributes["y"].value = newrec_y = rect_o_y + (svgBoxHeight - clicked_origin_y - 17 -2);

    text.attributes["y"].value = newrec_y + 12;
    text.attributes["x"].value = newrec_x + 4;

    rect.attributes["width"].value = rect.attributes["owidth"].value * scaleFactor;
  }

  adjust_text_size(e.ownerSVGElement);

}

function unzoom(e) {

  var svgOwner = e.ownerSVGElement;
  stack = svgOwner.zoomStack;

  // Unhighlight whatever was selected.
  if (selected != null)
    selected.classList.remove("s")


  // Stack management: Never remove the last element which is the flamegraph root.
  if (stack.length > 1) {
    previouslySelected = stack.pop();
    select(previouslySelected);
  }
  nextElement = stack[stack.length-1] // stack.peek()

  // Hide zoom out button.
  if (stack.length==1) {
    svgOwner.getElementById("zoom_rect").style.display = "none";
    svgOwner.getElementById("zoom_text").style.display = "none";
  }

  displayFromElement(nextElement);
  dumpStack(svgOwner);
}

function search(e) {
  var term = prompt("Search for:", "");

  var svgOwner = e.ownerSVGElement
  var callsites = e.ownerSVGElement.getElementsByTagName("g");

  if (term == null || term == "") {
    for (i = 0; i < callsites.length; i=i+1) {
      rect = callsites[i].getElementsByTagName("rect")[0];
      rect.attributes["fill"].value = rect.attributes["ofill"].value;
    }
    return;
  }

  for (i = 0; i < callsites.length; i=i+1) {
    title = callsites[i].getElementsByTagName("title")[0];
    rect = callsites[i].getElementsByTagName("rect")[0];
    if (title.textContent.indexOf(term) != -1) {
      rect.attributes["fill"].value = "rgb(230,100,230)";
    } else {
      rect.attributes["fill"].value = rect.attributes["ofill"].value;
    }
  }
}

var selected;
document.onkeydown = function handle_keyboard_input(e) {
  if (selected == null)
     return;

  title = selected.getElementsByTagName("title")[0];
  nav = selected.attributes["nav"].value.split(",")
  navigation_index = -1
  switch (e.keyCode) {
     //case 38: // ARROW UP
     case 87 : navigation_index = 0;break; //W

     //case 32 : // ARROW LEFT
     case 65 : navigation_index = 1;break; //A

     // case 43: // ARROW DOWN
     case 68 : navigation_index = 3;break; // S

     // case 39: // ARROW RIGHT
     case 83 : navigation_index = 2;break; // D

     case 32 : zoom(selected); return false; break; // SPACE

     case 8: // BACKSPACE
          unzoom(selected); return false;
     default: return true;
  }

  if (nav[navigation_index] == "0")
    return false;

  target_element = selected.ownerSVGElement.getElementById(nav[navigation_index]);
  select(target_element)
  return false;
}

function select(e) {
  if (selected != null)
    selected.classList.remove("s")
  selected = e;
  selected.classList.add("s")

  // Update info bar
  titleElement = selected.getElementsByTagName("title")[0];
  text = titleElement.textContent;

  // Parse title
  method_and_info = text.split(" | ");
  methodName = method_and_info[0];
  info =  method_and_info[1]

  // Parse info
  // '/system/lib64/libhwbinder.so (4 samples: 0.28%)'
  var regexp = /(.*) \(.* ([0-9**\.[0-9]*%)\)/g;
  match = regexp.exec(info);
  if (match.length > 2) {
    percentage = match[2]
    // Write percentage
    percentageTextElement = selected.ownerSVGElement.getElementById("percent_text")
    percentageTextElement.textContent = percentage
    //console.log("'" + percentage + "'")
  }

  // Set fields
  barTextElement = selected.ownerSVGElement.getElementById("info_text")
  barTextElement.textContent = methodName
}


</script>