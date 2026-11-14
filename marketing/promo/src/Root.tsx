import React from 'react';
import {Composition} from 'remotion';
import {Promo, FPS, TOTAL} from './Promo';

// Vertical 9:16 for Reels, TikTok and Shorts; 60 seconds.
export const Root: React.FC = () => (
  <Composition id="Promo" component={Promo} durationInFrames={TOTAL} fps={FPS} width={1080} height={1920} />
);
