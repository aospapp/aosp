#
# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import sys

SVG_CANVAS_WIDTH = 1124
SVG_NODE_HEIGHT = 17
FONT_SIZE = 12


def hash_to_float(string):
    return hash(string) / float(sys.maxint)

def getLegacyColor(method) :
    r = 175 + int(50 * hash_to_float(reversed(method)))
    g = 60 + int(180 * hash_to_float(method))
    b = 60 +int(55 * hash_to_float(reversed(method)))
    return (r,g,b)


def getDSOColor(method) :
    r = 170 + int(80 * hash_to_float(reversed(method)))
    g = 180 +int(70 * hash_to_float((method)))
    b = 170 + int(80 * hash_to_float(reversed(method)))
    return (r,g,b)


def getHeatColor(callsite, num_samples) :
    r = 245 + 10* (1- float(callsite.num_samples)/ num_samples)
    g = 110 + 105* (1-float(callsite.num_samples)/ num_samples)
    b = 100
    return (r,g,b)


def createSVGNode(callsite, depth, f, num_samples, height, color_scheme, nav):
    x = float(callsite.offset)/float(num_samples)*SVG_CANVAS_WIDTH
    y = height - (depth * SVG_NODE_HEIGHT) - SVG_NODE_HEIGHT
    width = float(callsite.num_samples) /float(num_samples) * SVG_CANVAS_WIDTH

    method = callsite.method.replace(">", "&gt;").replace("<", "&lt;")
    if (width <= 0) :
        return

    if color_scheme == "dso":
        r, g, b = getDSOColor(callsite.dso)
    elif color_scheme == "legacy":
        r, g, b = getLegacyColor(method)
    else:
        r, g, b = getHeatColor(callsite, num_samples)



    r_border = (r - 50)
    if r_border < 0:
        r_border = 0

    g_border = (g - 50)
    if g_border < 0:
        g_border = 0

    b_border = (b - 50)
    if (b_border < 0):
        b_border = 0

    f.write(
    '<g id=%d class="n" onclick="zoom(this);" onmouseenter="select(this);" nav="%s"> \n\
        <title>%s | %s (%d samples: %3.2f%%)</title>\n \
        <rect x="%f" y="%f" ox="%f" oy="%f" width="%f" owidth="%f" height="15.0" ofill="rgb(%d,%d,%d)" \
        fill="rgb(%d,%d,%d)" style="stroke:rgb(%d,%d,%d)"/>\n \
        <text x="%f" y="%f" font-size="%d" font-family="Monospace"></text>\n \
    </g>\n' % (callsite.id, ','.join(str(x) for x in nav),
               method, callsite.dso, callsite.num_samples, callsite.num_samples/float(num_samples) * 100,
               x, y, x, y, width , width, r, g, b, r, g, b, r_border, g_border, b_border,
               x+2, y+12, FONT_SIZE))


def renderSVGNodes(flamegraph, depth, f, num_samples, height, color_scheme):
    for i, callsite in enumerate(flamegraph.callsites):
        # Prebuild navigation target for wasd

        if i == 0:
            left_index = 0
        else:
            left_index = flamegraph.callsites[i-1].id

        if i == len(flamegraph.callsites)-1:
            right_index = 0
        else:
            right_index = flamegraph.callsites[i+1].id


        up_index = 0
        max_up = 0
        for upcallsite in callsite.callsites:
            if upcallsite.num_samples > max_up:
                max_up = upcallsite.num_samples
                up_index = upcallsite.id

        # up, left, down, right
        nav = [up_index, left_index,flamegraph.id,right_index]

        createSVGNode(callsite, depth, f, num_samples, height, color_scheme, nav)
        # Recurse down
        renderSVGNodes(callsite, depth+1, f, num_samples, height, color_scheme)

def renderSearchNode(f):
    f.write(
       '<rect id="search_rect"  style="stroke:rgb(0,0,0);" onclick="search(this);" class="t" rx="10" ry="10" \
       x="%d" y="10" width="80" height="30" fill="rgb(255,255,255)""/> \
        <text id="search_text"  class="t" x="%d" y="30"    onclick="search(this);">Search</text>\n'
       % (SVG_CANVAS_WIDTH - 95, SVG_CANVAS_WIDTH - 80)
    )


def renderUnzoomNode(f):
    f.write(
        '<rect id="zoom_rect" style="display:none;stroke:rgb(0,0,0);" class="t" onclick="unzoom(this);" \
        rx="10" ry="10" x="10" y="10" width="80" height="30" fill="rgb(255,255,255)"/> \
         <text id="zoom_text" style="display:none;" class="t" x="19" y="30"     \
         onclick="unzoom(this);">Zoom out</text>\n'
    )

def renderInfoNode(f):
    f.write(
        '<clipPath id="info_clip_path"> <rect id="info_rect" style="stroke:rgb(0,0,0);" \
        rx="10" ry="10" x="120" y="10" width="%d" height="30" fill="rgb(255,255,255)"/></clipPath> \
        <rect id="info_rect" style="stroke:rgb(0,0,0);" \
        rx="10" ry="10" x="120" y="10" width="%d" height="30" fill="rgb(255,255,255)"/> \
         <text clip-path="url(#info_clip_path)" id="info_text" x="128" y="30"></text>\n' % (SVG_CANVAS_WIDTH - 335, SVG_CANVAS_WIDTH - 325)
    )

def renderPercentNode(f):
    f.write(
        '<rect id="percent_rect" style="stroke:rgb(0,0,0);" \
        rx="10" ry="10" x="%d" y="10" width="82" height="30" fill="rgb(255,255,255)"/> \
         <text  id="percent_text" text-anchor="end" x="%d" y="30">100.00%%</text>\n' % (SVG_CANVAS_WIDTH - (95 * 2),SVG_CANVAS_WIDTH - (125))
    )


def renderSVG(flamegraph, f, color_scheme, width):
    global SVG_CANVAS_WIDTH
    SVG_CANVAS_WIDTH = width
    height = (flamegraph.get_max_depth() + 2 )* SVG_NODE_HEIGHT
    f.write('<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" \
    width="%d" height="%d" style="border: 1px solid black;" \
    onload="adjust_text_size(this);" rootid="%d">\n' % (SVG_CANVAS_WIDTH, height, flamegraph.callsites[0].id))
    f.write('<defs > <linearGradient id="background_gradiant" y1="0" y2="1" x1="0" x2="0" > \
    <stop stop-color="#eeeeee" offset="5%" /> <stop stop-color="#efefb1" offset="90%" /> </linearGradient> </defs>')
    f.write('<rect x="0.0" y="0" width="%d" height="%d" fill="url(#background_gradiant)"  />' % \
            (SVG_CANVAS_WIDTH, height))
    renderSVGNodes(flamegraph, 0, f, flamegraph.num_samples, height, color_scheme)
    renderSearchNode(f)
    renderUnzoomNode(f)
    renderInfoNode(f)
    renderPercentNode(f)
    f.write("</svg><br/>\n\n")