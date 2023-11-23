import {css, html, LitElement} from 'lit';
import {customElement, property} from 'lit/decorators.js';

@customElement('ns-pyramid-sprite')
export class PyramidSprite extends LitElement {
  @property({type: css, attribute: 'color'}) color;

  @property({type: css, attribute: 'size'}) size;

  constructor() {
    super();
    this.color ??= css`red`;
    this.size ??= css`30px`;
  }

  static styles = css`
    * {
      margin: 0;
      padding: 0;
    }

    :host {
      display: block;
      min-height: 1.5em;
      min-width: 1.5em;
      width: 1em;
      perspective: 1250px;
    }
    .pyramid {
      transform-style: preserve-3d;
      transform: rotateX(-15deg) rotateY(-15deg);
      position: absolute;
      left: 0.25em;
      bottom: 0.25em;
      width: 1em;
      height: 1em;
    }
    .pyramid > div {
      background-color: var(--color);
      position: absolute;
      width: 100%;
      height: 100%;
      clip-path: polygon(50% 0, 100% 100%, 0 100%);
      box-shadow: 0 0 0.25em #000 inset;
      opacity: 0.7;
    }
    .pyramid > div:nth-child(1) {
      transform: rotateX(30deg);
    }
    .pyramid > div:nth-child(2) {
      transform: translate3d(0.25em, 0, -0.25em) rotateY(90deg) rotateX(30deg);
    }
    .pyramid > div:nth-child(3) {
      transform: translate3d(0, 0, -0.5em) rotateY(180deg) rotateX(30deg);
    }
    .pyramid > div:nth-child(4) {
      transform: translate3d(-0.25em, 0, -0.25em) rotateY(270deg) rotateX(30deg);
    }
  `;

  render() {
    return html`
      <style>
        :host {
          font-size: ${this.size};
          --color: ${this.color};
        }
      </style>
      <div class="pyramid">
        <div class="face"></div>
        <div class="face"></div>
        <div class="face"></div>
        <div class="face"></div>
      </div>
    `;
  }
}
