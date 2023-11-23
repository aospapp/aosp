import{__decorate as i}from"../node_modules/tslib/tslib.es6.js";import{css as e,LitElement as t,html as s}from"https://cdn.jsdelivr.net/gh/lit/dist@2/core/lit-core.min.js";import{property as o,customElement as a}from"https://cdn.skypack.dev/pin/lit@v2.5.0-jYRq0AKQogjUdUh7SCAE/mode=imports/optimized/lit/decorators.js";import{styleMap as l,live as d}from"https://cdn.jsdelivr.net/gh/lit/dist@2/all/lit-all.min.js";import{simulationState as n}from"./device-observer.js";import{State as c}from"./model.js";var r;let h=r=class extends t{constructor(){super(...arguments),this.yaw=0,this.pitch=0,this.roll=0,this.editMode=!1,this.posX=0,this.posY=0,this.posZ=0,this.holdRange=!1}connectedCallback(){super.connectedCallback(),n.registerObserver(this)}disconnectedCallback(){n.removeObserver(this),super.disconnectedCallback()}onNotify(i){if(i.selectedId&&!1===this.editMode)for(const e of i.devices)if(e.name===i.selectedId){this.selectedDevice=e,this.holdRange||(this.yaw=e.orientation.yaw,this.pitch=e.orientation.pitch,this.roll=e.orientation.roll),this.posX=Math.floor(100*e.position.x),this.posY=Math.floor(100*e.position.y),this.posZ=Math.floor(100*e.position.z);break}}changeRange(i){var e;this.holdRange=!0,console.assert(null!==this.selectedDevice);const t=i.target,s=new CustomEvent("orientationEvent",{detail:{name:null===(e=this.selectedDevice)||void 0===e?void 0:e.name,type:t.id,value:t.value}});window.dispatchEvent(s),"yaw"===t.id?this.yaw=Number(t.value):"pitch"===t.id?this.pitch=Number(t.value):this.roll=Number(t.value)}patchOrientation(){this.holdRange=!1,console.assert(void 0!==this.selectedDevice),void 0!==this.selectedDevice&&(this.selectedDevice.orientation={yaw:this.yaw,pitch:this.pitch,roll:this.roll},n.patchDevice({device:{name:this.selectedDevice.name,orientation:this.selectedDevice.orientation}}))}patchRadio(){console.assert(void 0!==this.selectedDevice),void 0!==this.selectedDevice&&n.patchDevice({device:{name:this.selectedDevice.name,chips:this.selectedDevice.chips}})}handleEditForm(){this.editMode?(n.invokeGetDevice(),this.editMode=!1):this.editMode=!0}static checkPositionBound(i){return i>10?10:i<0?0:i}static checkOrientationBound(i){return i>90?90:i<-90?-90:i}handleSave(){if(console.assert(void 0!==this.selectedDevice),void 0===this.selectedDevice)return;const i=this.renderRoot.querySelectorAll('[id^="edit"]'),e={name:this.selectedDevice.name,position:this.selectedDevice.position,orientation:this.selectedDevice.orientation};i.forEach((i=>{const t=i;"editName"===t.id?e.name=t.value:t.id.startsWith("editPos")?Number.isNaN(Number(t.value))||(e.position[t.id.slice(7).toLowerCase()]=r.checkPositionBound(Number(t.value)/100)):t.id.startsWith("editOri")&&(Number.isNaN(Number(t.value))||(e.orientation[t.id.slice(7).toLowerCase()]=r.checkOrientationBound(Number(t.value))))})),this.selectedDevice.name=e.name,this.selectedDevice.position=e.position,this.selectedDevice.orientation=e.orientation,this.handleEditForm(),n.patchDevice({device:e})}handleGetChips(){const i=s`
      <input type="checkbox" disabled />
        <span
          class="slider round"
          style=${l({opacity:"0.7"})}
        ></span>
    `;let e=i,t=i,o=i,a=i;if(this.selectedDevice&&"chips"in this.selectedDevice&&this.selectedDevice.chips)for(const i of this.selectedDevice.chips)"bt"in i&&i.bt&&("lowEnergy"in i.bt&&i.bt.lowEnergy&&"state"in i.bt.lowEnergy&&(e=s`
                <input
                  id="lowEnergy"
                  type="checkbox"
                  .checked=${d(i.bt.lowEnergy.state===c.ON)}
                  @click=${()=>{var e;null===(e=this.selectedDevice)||void 0===e||e.toggleChipState(i,"lowEnergy"),this.patchRadio()}}
                />
                <span class="slider round"></span>
              `),"classic"in i.bt&&i.bt.classic&&"state"in i.bt.classic&&(t=s`
                <input
                  id="classic"
                  type="checkbox"
                  .checked=${d(i.bt.classic.state===c.ON)}
                  @click=${()=>{var e;null===(e=this.selectedDevice)||void 0===e||e.toggleChipState(i,"classic"),this.patchRadio()}}
                />
                <span class="slider round"></span>
              `)),"wifi"in i&&i.wifi&&(o=s`
              <input
                id="wifi"
                type="checkbox"
                .checked=${d(i.wifi.state===c.ON)}
                @click=${()=>{var e;null===(e=this.selectedDevice)||void 0===e||e.toggleChipState(i),this.patchRadio()}}
              />
              <span class="slider round"></span>
            `),"uwb"in i&&i.uwb&&(a=s`
              <input
                id="uwb"
                type="checkbox"
                .checked=${d(i.uwb.state===c.ON)}
                @click=${()=>{var e;null===(e=this.selectedDevice)||void 0===e||e.toggleChipState(i),this.patchRadio()}}
              />
              <span class="slider round"></span>
            `);return s`
      <div class="label">BLE</div>
      <div class="info">
        <label class="switch">
          ${e}
        </label>
      </div>
      <div class="label">Classic</div>
      <div class="info">
        <label class="switch">
          ${t}
        </label>
      </div>
      <div class="label">WIFI</div>
      <div class="info">
        <label class="switch">
          ${o}
        </label>
      </div>
      <div class="label">UWB</div>
      <div class="info">
        <label class="switch">
          ${a}
        </label>
      </div>
    `}render(){return s`${this.selectedDevice?s`
          <div class="title">Device Info</div>
          <div class="setting">
            <div class="name">Name</div>
            <div class="info">${this.selectedDevice.name}</div>
          </div>
          <div class="setting">
            <div class="name">Position</div>
            <div class="label">X</div>
            <div class="info" style=${l({color:"red"})}>
              ${this.editMode?s`<input
                    type="text"
                    id="editPosX"
                    .value=${this.posX.toString()}
                  />`:s`${this.posX}`}
            </div>
            <div class="label">Y</div>
            <div class="info" style=${l({color:"green"})}>
              ${this.editMode?s`<input
                    type="text"
                    id="editPosY"
                    .value=${this.posY.toString()}
                  />`:s`${this.posY}`}
            </div>
            <div class="label">Z</div>
            <div class="info" style=${l({color:"blue"})}>
              ${this.editMode?s`<input
                    type="text"
                    id="editPosZ"
                    .value=${this.posZ.toString()}
                  />`:s`${this.posZ}`}
            </div>
          </div>
          <div class="setting">
            <div class="name">Orientation</div>
            <div class="label">Yaw</div>
            <div class="info">
              <input
                id="yaw"
                type="range"
                min="-90"
                max="90"
                .value=${this.yaw.toString()}
                .disabled=${this.editMode}
                @input=${this.changeRange}
                @change=${this.patchOrientation}
              />
              ${this.editMode?s`<input
                    type="text"
                    id="editOriYaw"
                    class="orientation"
                    .value=${this.yaw.toString()}
                  />`:s`<div class="text">(${this.yaw})</div>`}
            </div>
            <div class="label">Pitch</div>
            <div class="info">
              <input
                id="pitch"
                type="range"
                min="-90"
                max="90"
                .value=${this.pitch.toString()}
                .disabled=${this.editMode}
                @input=${this.changeRange}
                @change=${this.patchOrientation}
              />
              ${this.editMode?s`<input
                    type="text"
                    id="editOriPitch"
                    class="orientation"
                    .value=${this.pitch.toString()}
                  />`:s`<div class="text">(${this.pitch})</div>`}
            </div>
            <div class="label">Roll</div>
            <div class="info">
              <input
                id="roll"
                type="range"
                min="-90"
                max="90"
                .value=${this.roll.toString()}
                .disabled=${this.editMode}
                @input=${this.changeRange}
                @change=${this.patchOrientation}
              />
              ${this.editMode?s`<input
                    type="text"
                    id="editOriRoll"
                    class="orientation"
                    .value=${this.roll.toString()}
                  />`:s`<div class="text">(${this.roll})</div>`}
            </div>
          </div>
          <div class="setting">
            ${this.editMode?s`
                  <input type="button" value="Save" @click=${this.handleSave} />
                  <input
                    type="button"
                    value="Cancel"
                    @click=${this.handleEditForm}
                  />
                `:s`<input
                  type="button"
                  value="Edit"
                  @click=${this.handleEditForm}
                />`}
          </div>
          <div class="setting">
            <div class="name">Radio States</div>
            ${this.handleGetChips()}
          </div>
        `:s`<div class="title">Device Info</div>`}`}};h.styles=e`
    :host {
      cursor: pointer;
      display: grid;
      place-content: center;
      color: white;
      font-size: 25px;
      font-family: 'Lato', sans-serif;
      border: 5px solid black;
      border-radius: 12px;
      padding: 10px;
      background-color: #9199a5;
      max-width: 600px;
    }

    .title {
      font-weight: bold;
      text-transform: uppercase;
      text-align: center;
      margin-bottom: 10px;
    }

    .setting {
      display: grid;
      grid-template-columns: auto auto;
      margin-top: 0px;
      margin-bottom: 30px;
      //border: 3px solid black;
      padding: 10px;
    }

    .setting .name {
      grid-column: 1 / span 2;
      text-transform: uppercase;
      text-align: left;
      margin-bottom: 10px;
      font-weight: bold;
    }

    .label {
      grid-column: 1;
      text-align: left;
    }

    .info {
      grid-column: 2;
      text-align: right;
      margin-bottom: 10px;
    }

    .switch {
      position: relative;
      float: right;
      width: 60px;
      height: 34px;
    }

    .switch input {
      opacity: 0;
      width: 0;
      height: 0;
    }

    .slider {
      position: absolute;
      cursor: pointer;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background-color: #ccc;
      -webkit-transition: 0.4s;
      transition: 0.4s;
    }

    .slider:before {
      position: absolute;
      content: '';
      height: 26px;
      width: 26px;
      left: 4px;
      bottom: 4px;
      background-color: white;
      -webkit-transition: 0.4s;
      transition: 0.4s;
    }

    input:checked + .slider {
      background-color: #2196f3;
    }

    input:focus + .slider {
      box-shadow: 0 0 1px #2196f3;
    }

    input:checked + .slider:before {
      -webkit-transform: translateX(26px);
      -ms-transform: translateX(26px);
      transform: translateX(26px);
    }

    /* Rounded sliders */
    .slider.round {
      border-radius: 34px;
    }

    .slider.round:before {
      border-radius: 50%;
    }

    .text {
      display: inline-block;
      position: relative;
      width: 50px;
    }

    input[type='range'] {
      width: 400px;
    }

    input[type='text'] {
      width: 50%;
      font-size: inherit;
      text-align: right;
      max-height: 25px;
    }

    input[type='text'].orientation {
      max-width: 50px;
    }

    input[type='button'] {
      display: inline;
      font-size: inherit;
      max-width: 200px;
    }
  `,i([o()],h.prototype,"selectedDevice",void 0),i([o({type:Number})],h.prototype,"yaw",void 0),i([o({type:Number})],h.prototype,"pitch",void 0),i([o({type:Number})],h.prototype,"roll",void 0),i([o({type:Boolean})],h.prototype,"editMode",void 0),i([o({type:Number})],h.prototype,"posX",void 0),i([o({type:Number})],h.prototype,"posY",void 0),i([o({type:Number})],h.prototype,"posZ",void 0),h=r=i([a("ns-device-info")],h);export{h as DeviceInformation};
